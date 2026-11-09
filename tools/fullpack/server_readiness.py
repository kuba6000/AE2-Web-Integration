"""Wait for Forge's actual status/login gate, rather than an open TCP port."""
import ipaddress
import json
import socket
import struct
import time


def endpoint(address):
    host, port = address.rsplit(':', 1)
    host = host.strip('[]')
    if host != 'localhost' and not ipaddress.ip_address(host).is_loopback:
        raise ValueError('Offline multiplayer must use a loopback address')
    port = int(port)
    if not 1 <= port <= 65535:
        raise ValueError('Invalid server port')
    return host, port


def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def read_exact(connection, count, deadline):
    result = bytearray()
    while len(result) < count:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Status response deadline expired')
        connection.settimeout(min(5, remaining))
        chunk = connection.recv(count - len(result))
        if not chunk:
            raise OSError('Status connection closed')
        result.extend(chunk)
    return bytes(result)


def read_varint(read):
    value = 0
    for shift in range(0, 35, 7):
        byte = read(1)[0]
        value |= (byte & 127) << shift
        if not byte & 128:
            return value
    raise ValueError('Invalid status VarInt')


def status_ready(host, port, deadline, protocol):
    with socket.create_connection((host, port), timeout=min(5, max(0.001, deadline - time.monotonic()))) as connection:
        address = host.encode('utf-8')
        handshake = b'\x00' + varint(protocol) + varint(len(address)) + address + struct.pack('>H', port) + b'\x01'
        connection.sendall(varint(len(handshake)) + handshake + b'\x01\x00')
        read = lambda count: read_exact(connection, count, deadline)
        size = read_varint(read)
        if not 1 <= size <= 1024 * 1024:
            raise ValueError('Unexpected status packet size')
        import io
        packet = io.BytesIO(read(size))
        if read_varint(packet.read) != 0:
            return False
        length = read_varint(packet.read)
        body = packet.read(length)
        if len(body) != length or packet.read(1):
            return False
        status = json.loads(body)
        if not isinstance(status, dict):
            return False
        version, players = status.get('version'), status.get('players')
        return (isinstance(version, dict) and version.get('protocol') == protocol
                and isinstance(version.get('name'), str) and bool(version['name'])
                and isinstance(players, dict)
                and all(type(players.get(key)) is int and players[key] >= 0 for key in ('online', 'max')))


def wait_for_server(address, timeout, protocol):
    if type(protocol) is not int or not 0 <= protocol <= 2147483647:
        raise ValueError('Invalid Minecraft protocol')
    host, port = endpoint(address)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if status_ready(host, port, deadline, protocol):
                return
        except (OSError, ValueError, IndexError, UnicodeError):
            pass
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(1, remaining))
    raise TimeoutError('Server did not become ready for the selected Minecraft protocol before the readiness deadline')
