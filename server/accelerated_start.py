"""Wait for the Android parent's native-filter confirmation before opening server data."""
import runpy
import sys

RELEASE=b'LSB_SERVER_FILTER_GO_V1\n'


def main():
    if sys.stdin.buffer.readline(64)!=RELEASE:
        return 1
    # The supervisor retains its normal database, readiness and shutdown paths.
    runpy.run_path('/opt/lsb-server/manager.py',run_name='__main__')
    return 0


if __name__=='__main__':sys.exit(main())
