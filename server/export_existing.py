#!/usr/bin/env python3
"""Run inside the existing Termux Linux distro; writes ZIP and SQL.gz backups."""
import argparse
import getpass
import gzip
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('server',type=Path,help='Existing server folder (with settings, scripts and sql)')
    p.add_argument('--output',type=Path,default=Path.cwd())
    a=p.parse_args();root=a.server.resolve();a.output.mkdir(parents=True,exist_ok=True)
    if not all((root/n).exists() for n in ('CMakeLists.txt','src','sql','settings')):p.error('Choose the full existing server folder')
    database=input('Existing database name [xidb]: ').strip() or 'xidb'
    user=input('Database user [root]: ').strip() or 'root'
    password=getpass.getpass('Database password (leave empty for socket login): ')
    host=input('Database host [localhost]: ').strip() or 'localhost'
    for value in (database,user,password,host):
        if any(c in value for c in '\r\n\0'):raise ValueError('Multiline database fields are not supported')
    dump=shutil.which('mariadb-dump') or shutil.which('mysqldump')
    if not dump:raise RuntimeError('Run this inside the Linux distro where your existing server database runs')
    target=a.output/'lsb-database.sql.gz';partial=target.with_suffix('.part')
    with tempfile.TemporaryDirectory() as temp:
        config=Path(temp)/'client.cnf'
        def quote(s):return '"'+s.replace('\\','\\\\').replace('"','\\"')+'"'
        config.write_text('[client]\nuser='+quote(user)+'\npassword='+quote(password)+'\nhost='+quote(host)+'\n');config.chmod(0o600)
        with (Path(temp)/'error').open('wb') as error:
            process=subprocess.Popen([dump,'--defaults-extra-file='+str(config),'--single-transaction','--routines','--triggers','--events','--hex-blob',database],stdout=subprocess.PIPE,stderr=error)
            with gzip.open(partial,'wb',compresslevel=3) as out:shutil.copyfileobj(process.stdout,out,1024*1024)
            process.stdout.close()
            if process.wait():partial.unlink(missing_ok=True);raise RuntimeError('Database export failed; check database name, user and password')
    partial.replace(target)
    archive=a.output/'lsb-existing-server.zip';tempzip=archive.with_suffix('.part')
    excluded={'.git','build','logs','log','.venv','venv','__pycache__'}
    with zipfile.ZipFile(tempzip,'w',zipfile.ZIP_DEFLATED,compresslevel=3) as z:
        for file in root.rglob('*'):
            relative=file.relative_to(root)
            if any(part in excluded for part in relative.parts) or file.resolve() in (archive.resolve(),tempzip.resolve(),target.resolve()):continue
            if file.is_file():
                if file.is_symlink() and not file.resolve().is_relative_to(root):continue
                z.write(file,'server/'+relative.as_posix())
        for name in ('xi_connect','xi_map','xi_search','xi_world'):
            if not (root/name).is_file():
                matches=list((root/'build').rglob(name))
                if len(matches)==1:z.write(matches[0],'server/'+name)
    tempzip.replace(archive)
    print('Import these in LSB Android → Server:')
    print(archive.resolve());print(target.resolve())
    print('The existing files and database were read only. Stop the Termux server before starting the managed server.')
if __name__=='__main__':main()
