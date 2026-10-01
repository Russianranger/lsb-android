"""Exercise the staged accept-result spelling with real ASIO socket lifetimes."""

from pathlib import Path
import re
import subprocess


PREAMBLE = r'''
#include <asio.hpp>
#include <cassert>
#include <cerrno>
#include <fcntl.h>
#include <iostream>
#include <tuple>
#include <utility>

asio::awaitable<void> accept_once(asio::ip::tcp::acceptor& acceptor_, bool transfer)
{
    int accepted_fd = -1;
    asio::ip::tcp::socket retained(co_await asio::this_coro::executor);
    {
'''

POSTAMBLE = r'''
        assert(!ec && socket.is_open());
        accepted_fd = socket.native_handle();
        assert(::fcntl(accepted_fd, F_GETFD) != -1);
        if (transfer)
            retained = std::move(socket);
    }
    // The named tuple owns its socket until scope exit, unless moved out.
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
        asio::io_context context;
        asio::ip::tcp::acceptor acceptor(context, {asio::ip::address_v4::loopback(), 0});
        asio::ip::tcp::socket peer(context);
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
    std::cout << "PASS: real ASIO accept tuple destroys or transfers socket ownership correctly\n";
}
'''


def check(root, output, compiler='g++-15'):
    root, output = Path(root), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    pattern = r'auto acceptResult = co_await acceptor_\.async_accept\(asio::as_tuple\(asio::use_awaitable\)\);\s*const auto& ec = std::get<0>\(acceptResult\);\s*auto& socket = std::get<1>\(acceptResult\);'
    fragments = []
    for name in ('src/search/search_listener.h', 'src/login/handler.h'):
        match = re.search(pattern, (root / name).read_text())
        assert match, name + ' must retain a named accept tuple'
        fragments.append(match.group(0))
    assert fragments[0] == fragments[1]
    candidates = list((root / '.cpm-cache/asio').glob('*/include/asio.hpp'))
    assert len(candidates) == 1, 'Expected the actual pinned ASIO dependency'
    fixture = output / 'accept-loop-lifetime.cpp'
    fixture.write_text(PREAMBLE + fragments[0] + '\n' + POSTAMBLE)
    executable = output / 'accept-loop-lifetime'
    commands = ([compiler, '-std=c++23', '-O3', '-Wall', '-Wextra', '-Werror',
                 '-Wno-error=null-dereference', '-Wno-error=mismatched-new-delete',
                 '-DASIO_STANDALONE', '-DASIO_NO_DEPRECATED', '-DASIO_RECYCLING_ALLOCATOR_CACHE_SIZE=8',
                 '-isystem', str(candidates[0].parent), '-pthread', str(fixture), '-o', str(executable)],
                [str(executable)])
    with (output / 'accept-loop-lifetime.log').open('w') as stream:
        for command in commands:
            stream.write('$ ' + ' '.join(command) + '\n')
            stream.flush()
            subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=120)
    return dict(state='passed', cases=2, coverage=['scope-exit socket close', 'explicit ownership transfer'])
