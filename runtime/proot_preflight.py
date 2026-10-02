"""Credential-free syscall/filter compatibility check before the real runtime.

No game or prefix data is read. All output is a fixed marker or fixed failure.
The Android parent imposes a timeout and reaps this entire tree before login.
"""
import ctypes
import fcntl
import mmap
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading


def check(sysvipc=False, directory='/tmp', server=False):
    with tempfile.TemporaryDirectory(prefix='lsb-filter-', dir=directory) as temporary:
        path=Path(temporary)/'io.bin'
        payload=bytes(range(256))*16
        with path.open('w+b', buffering=0) as stream:
            if stream.write(payload)!=len(payload):raise RuntimeError('file')
            stream.seek(0)
            if stream.read()!=payload:raise RuntimeError('file')
            with mmap.mmap(stream.fileno(),len(payload)) as mapping:
                mapping[:4]=b'LSB1'
                if mapping[:4]!=b'LSB1':raise RuntimeError('mmap')
            if server:
                fcntl.flock(stream.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
                os.fsync(stream.fileno())
                fcntl.flock(stream.fileno(),fcntl.LOCK_UN)
        path.rename(Path(temporary)/'renamed.bin')
    left,right=socket.socketpair()
    try:
        left.settimeout(2);right.settimeout(2);left.sendall(b'LSB1')
        if right.recv(4)!=b'LSB1':raise RuntimeError('socket')
    finally:left.close();right.close()
    ready=threading.Event();worker=threading.Thread(target=ready.set);worker.start()
    if not ready.wait(2):raise RuntimeError('thread')
    worker.join(2)
    if worker.is_alive():raise RuntimeError('thread')
    read,write=os.pipe();pid=os.fork()
    if pid==0:
        os.close(read)
        try:os.write(write,b'LSB1')
        finally:os.close(write)
        os._exit(0)
    os.close(write)
    try:
        if os.read(read,4)!=b'LSB1':raise RuntimeError('pipe')
        if os.waitpid(pid,0)[1]!=0:raise RuntimeError('fork')
    finally:os.close(read)
    if server:
        # Private ephemeral loopback sockets and harmless native execs only.
        # Neither MariaDB's data directory nor server generations are opened.
        with socket.socket() as listener:
            listener.settimeout(2);listener.bind(('127.0.0.1',0));listener.listen(1)
            with socket.create_connection(listener.getsockname(),timeout=2) as client:
                with listener.accept()[0] as accepted:
                    accepted.settimeout(2);client.sendall(b'LSB1')
                    if accepted.recv(4)!=b'LSB1':raise RuntimeError('tcp')
        for command in (['/usr/bin/true'],['/usr/sbin/mariadbd','--no-defaults','--version']):
            subprocess.run(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,timeout=5)
    if sysvipc:
        libc=ctypes.CDLL(None,use_errno=True)
        libc.shmget.argtypes=[ctypes.c_int,ctypes.c_size_t,ctypes.c_int];libc.shmget.restype=ctypes.c_int
        libc.shmat.argtypes=[ctypes.c_int,ctypes.c_void_p,ctypes.c_int];libc.shmat.restype=ctypes.c_void_p
        libc.shmdt.argtypes=[ctypes.c_void_p];libc.shmdt.restype=ctypes.c_int
        libc.shmctl.argtypes=[ctypes.c_int,ctypes.c_int,ctypes.c_void_p];libc.shmctl.restype=ctypes.c_int
        segment=libc.shmget(0,4096,0o1000|0o600)
        if segment<0:raise RuntimeError('shmget')
        addresses=[]
        try:
            for _ in range(2):
                address=libc.shmat(segment,None,0)
                if address==ctypes.c_void_p(-1).value:raise RuntimeError('shmat')
                addresses.append(address)
            ctypes.memmove(addresses[0],b'LSB1',4)
            if ctypes.string_at(addresses[1],4)!=b'LSB1':raise RuntimeError('shm')
        finally:
            cleanup_failed=False
            for address in addresses:
                if libc.shmdt(address)!=0:cleanup_failed=True
            if libc.shmctl(segment,0,None)!=0:cleanup_failed=True
            if cleanup_failed:raise RuntimeError('shm_cleanup')


if __name__=='__main__':
    try:
        if sys.argv[1:] not in ([],['--sysvipc'],['--server']):raise RuntimeError('arguments')
        check(sysvipc=sys.argv[1:]==['--sysvipc'],server=sys.argv[1:]==['--server']);print('LSB_PROOT_PREFLIGHT_V1 PASS',flush=True)
    except BaseException:
        print('LSB_PROOT_PREFLIGHT_V1 FAIL',flush=True);sys.exit(1)
