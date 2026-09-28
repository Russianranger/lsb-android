"""Compile the exact patched profile accept expression against its real ASIO."""

from pathlib import Path
import re
import subprocess


PREAMBLE = r'''
#include <asio.hpp>
#include <cassert>
#include <cerrno>
#include <chrono>
#include <fcntl.h>
#include <iostream>
#include <tuple>
#include <utility>

asio::awaitable<void> accept_once(asio::ip::tcp::acceptor& acceptor, bool transfer)
{
    int accepted_fd = -1;
    asio::ip::tcp::socket retained(co_await asio::this_coro::executor);
    {
'''

POSTAMBLE = r'''
        assert(!ec && socket.is_open());
        // The real profile loop passes ec as an output parameter here. The
        // login/search patch's const reference would not compile in this role.
        const auto peer = socket.remote_endpoint(ec);
        assert(!ec && peer.address().is_loopback());
        accepted_fd = socket.native_handle();
        assert(::fcntl(accepted_fd, F_GETFD) != -1);
        if (transfer)
            retained = std::move(socket);
    }
    if (transfer) {
        assert(retained.is_open());
        assert(::fcntl(accepted_fd, F_GETFD) != -1);
        retained.close();
    }
    errno = 0;
    assert(::fcntl(accepted_fd, F_GETFD) == -1 && errno == EBADF);
}

int main()
{
    for (bool transfer : {false, true}) {
        for (bool deferred : {false, true}) {
            asio::io_context context;
            asio::ip::tcp::acceptor acceptor(context, {asio::ip::address_v4::loopback(), 0});
            asio::ip::tcp::socket peer(context);
            asio::steady_timer timer(context, std::chrono::milliseconds(5));
            if (deferred)
                timer.async_wait([&](const asio::error_code& error) {
                    assert(!error);
                    peer.connect(acceptor.local_endpoint());
                });
            else
                peer.connect(acceptor.local_endpoint());
            bool completed = false;
            asio::co_spawn(context, accept_once(acceptor, transfer),
                [&](std::exception_ptr error) {
                    if (error) std::rethrow_exception(error);
                    completed = true;
                });
            context.run();
            assert(completed);
        }
    }
    std::cout << "PASS: profile accept suspension, mutable error code and socket ownership\n";
}
'''


def check(root, output, compiler='g++-15'):
    root, output = Path(root), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    source = (root / 'src/profile/profile_engine.cpp').read_text()
    pattern = (r'auto acceptResult = co_await acceptor\.async_accept\(asio::as_tuple\(asio::use_awaitable\)\);\s*'
               r'auto& ec = std::get<0>\(acceptResult\);\s*auto& socket = std::get<1>\(acceptResult\);')
    matches = re.findall(pattern, source)
    assert len(matches) == 1, 'Profile accept loop must keep one named tuple and a mutable error code'
    candidates = list((root / '.cpm-cache/asio').glob('*/include/asio.hpp'))
    assert len(candidates) == 1, 'Expected the actual pinned ASIO dependency'
    fixture = output / 'profile-accept-lifetime.cpp'
    fixture.write_text(PREAMBLE + matches[0] + '\n' + POSTAMBLE)
    executable = output / 'profile-accept-lifetime'
    commands = ([compiler, '-std=c++23', '-O3', '-Wall', '-Wextra', '-Werror',
                 '-Wno-error=null-dereference', '-Wno-error=mismatched-new-delete',
                 '-DASIO_STANDALONE', '-DASIO_NO_DEPRECATED', '-DASIO_RECYCLING_ALLOCATOR_CACHE_SIZE=8',
                 '-isystem', str(candidates[0].parent), '-pthread', str(fixture), '-o', str(executable)],
                [str(executable)])
    with (output / 'profile-accept-lifetime.log').open('w') as stream:
        for command in commands:
            stream.write('$ ' + ' '.join(command) + '\n')
            stream.flush()
            subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=120)
    return dict(state='passed', cases=4,
                coverage=['immediate accept', 'suspended accept', 'mutable endpoint error',
                          'scope-exit socket close', 'explicit ownership transfer'])
