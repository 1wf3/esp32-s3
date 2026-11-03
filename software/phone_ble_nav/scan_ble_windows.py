"""Read-only Windows BLE scan. Dependencies live only in build/ble_tools."""
import asyncio
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / 'build' / 'ble_tools'))
from bleak import BleakScanner


async def main():
    found = await BleakScanner.discover(timeout=15, return_adv=True)
    matched = 0
    for device, adv in found.values():
        if adv.local_name == 'MOTO GPS TFT' or device.name == 'MOTO GPS TFT':
            matched += 1
            print('FOUND MOTO GPS TFT', 'RSSI=', adv.rssi, 'UUIDs=', adv.service_uuids)
    print('Matching devices:', matched)
    if not matched:
        sys.exit(2)


if __name__ == '__main__':
    asyncio.run(main())
