"""Regenerate app/src/test/resources/golden.json from keloray-control.

The Android app ports keloray-control's solar, reef, channel and schema
normalization logic to Kotlin. GoldenTest.kt replays these cases against the
Kotlin port, so the two stay numerically identical.

Run from the repo root:

    python keloray-android/tools/gen_golden.py
"""
import asyncio
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "keloray-control"))

from keloray import reef, solar  # noqa: E402
from keloray.client import GizwitsClient  # noqa: E402


def sun_cases():
    out = []
    singapore = dict(latitude_deg=1.35, longitude_deg=103.82, utc_offset_hours=8.0)
    cape_town = dict(latitude_deg=-33.92, longitude_deg=18.42, utc_offset_hours=2.0)
    for dt in [(2026, 6, 28, h, m) for h, m in
               [(4, 0), (6, 50), (7, 10), (7, 30), (9, 0), (13, 0), (18, 30), (19, 15), (19, 30), (19, 45), (23, 0)]]:
        out.append({"dt": dt, "loc": singapore, "lux_scale": 1.0, "cct_bump_k": 0.0,
                    "frame": solar.sun_channels(dt, **singapore)})
    for dt in [(2026, 12, 21, 5, 40), (2026, 12, 21, 12, 0), (2026, 12, 21, 20, 10), (2026, 3, 1, 9, 0)]:
        out.append({"dt": dt, "loc": cape_town, "lux_scale": 1.0, "cct_bump_k": 0.0,
                    "frame": solar.sun_channels(dt, **cape_town)})
    for dt in [(2026, 12, 2, 13, 0), (2026, 12, 2, 7, 15)]:
        out.append({"dt": dt, "loc": singapore, "lux_scale": 0.6, "cct_bump_k": 600.0,
                    "frame": solar.sun_channels(dt, lux_scale=0.6, cct_bump_k=600.0, **singapore)})
    return out


def elevation_cases():
    out = []
    for doy, hour, lat, lon, utc in [(1, 12.0, 1.35, 103.82, 8.0), (172, 7.25, 1.35, 103.82, 8.0),
                                    (355, 12.5, -33.92, 18.42, 2.0), (80, 18.9, 51.5, -0.12, 0.0),
                                    (200, 0.0, 64.1, -21.9, 0.0)]:
        out.append({"doy": doy, "hour": hour, "lat": lat, "lon": lon, "utc": utc,
                    "elev": solar.solar_elevation(doy, hour, lat, lon, utc)})
    return out


def reef_cases():
    out = []
    for elev, kw in [(-12, {}), (-12, {"moonlight": 20}), (-2, {}), (0.5, {}), (2, {}), (10, {}),
                     (30, {}), (50, {}), (80, {}), (80, {"caps": {"rb": 50, "uv1": 60}}),
                     (45, {"intensity_scale": 0.55}), (-3.0, {"moonlight": 8})]:
        out.append({"elev": elev, "kw": kw, "frame": reef.reef_channels(elev, **kw)})
    return out


SCHEMAS = {
    "packed": {"entities": [{"attrs": [
        {"name": "onOff", "data_type": "bool", "type": "status_writable"},
        {"name": "Brightness", "data_type": "uint8", "type": "status_writable", "uint_spec": {"min": 0, "max": 255}},
        {"name": "Color_HSV", "data_type": "binary", "type": "status_writable"},
        {"name": "Temp", "data_type": "uint8", "type": "status_writable", "uint_spec": {"min": 0, "max": 1000}},
    ]}]},
    "split": {"attrs": [
        {"name": "Hue", "type": "uint16", "uint_spec": {"min": 0, "max": 359}},
        {"name": "Saturation", "type": "uint8", "uint_spec": {"min": 0, "max": 254}},
        {"name": "Value", "type": "uint8", "uint_spec": {"min": 0, "max": 254}},
        {"name": "lum", "type": "uint8", "uint_spec": {"min": 1, "max": 100}},
        {"name": "Power_Switch", "data_type": "bool", "type": "status_writable"},
    ]},
    "components": {"attrs": [
        {"name": "color_h", "data_type": "uint16", "min": 0, "max": 360},
        {"name": "color_s", "data_type": "uint8", "min": 0, "max": 100},
        {"name": "color_v", "data_type": "uint8", "min": 0, "max": 100},
        {"name": "LUM", "data_type": "uint8", "min": 0, "max": 1000},
    ]},
    "coral": {"entities": [{"attrs": [
        {"name": "Switch", "data_type": "bool", "type": "status_writable"},
        {"name": "Cold_white", "data_type": "uint16", "type": "status_writable", "uint_spec": {"min": 0, "max": 1000}},
        {"name": "Royal_blue", "data_type": "uint16", "type": "status_writable", "uint_spec": {"min": 0, "max": 1000}},
        {"name": "UV1", "data_type": "uint8", "type": "status_writable", "uint_spec": {"min": 0, "max": 255}},
    ]}]},
    "unknown": {"foo": "bar", "list": [1, 2, 3]},
}

ATTRS = [
    {"onOff": 1, "lum": 50, "temperature": 30, "hsv": {"h": 120, "s": 50, "v": 80}},
    {"lum": 1, "temperature": 100},
    {"hsv": {"h": 359, "s": 100, "v": 100}},
    {"onOff": 0, "Royal_blue": 500, "Cold_white": 1000, "UV1": 128},
    {"mystery": 7},
]


def normalize_cases():
    out = []
    c = GizwitsClient(app_id="x", region="cn")
    for name, schema in SCHEMAS.items():
        for attrs in ATTRS:
            async def run():
                async def fake_schema(_did):
                    return schema
                c._schema_for = fake_schema
                return await c._normalized("D", attrs)
            out.append({"schema": name, "attrs": attrs, "out": asyncio.run(run())})
    asyncio.run(c.aclose())
    return out


def main():
    data = {
        "sun": sun_cases(),
        "elevation": elevation_cases(),
        "reef": reef_cases(),
        "schemas": SCHEMAS,
        "normalize": normalize_cases(),
    }
    dest = Path(__file__).resolve().parents[1] / "app/src/test/resources/golden.json"
    dest.write_text(json.dumps(data, indent=1) + "\n")
    print(f"wrote {dest} ({len(data['sun'])} sun, {len(data['reef'])} reef, "
          f"{len(data['normalize'])} normalize cases)")


if __name__ == "__main__":
    main()
