"""Offline tests for the REST API used by the dashboard and the Android app."""
from fastapi.testclient import TestClient

from keloray import server
from keloray.channels import ChannelMap
from keloray.effects import SceneRunner


class _FakeClient:
    def __init__(self):
        self.writes = []

    async def control(self, did, attrs):
        self.writes.append((did, attrs))
        return {"ok": True}


def _client_with_fakes(channel_map=None):
    fake = _FakeClient()
    server.state.client = fake
    server.state.runner = SceneRunner(fake, min_interval=0.0, channel_map=channel_map)
    # no `with`: skip the lifespan, which needs real credentials
    return TestClient(server.app), fake


def test_control_passes_plain_attrs_through():
    http, fake = _client_with_fakes()
    r = http.post("/api/devices/D1/control", json={"attrs": {"onOff": 1, "lum": 60}})
    assert r.status_code == 200
    assert fake.writes == [("D1", {"onOff": 1, "lum": 60})]


def test_control_expands_coral_channels():
    http, fake = _client_with_fakes(ChannelMap({
        "cw": "Cold_white", "rb": {"name": "Royal_blue", "hi": 1000}}))
    r = http.post("/api/devices/D1/control",
                  json={"attrs": {"onOff": 1, "channels": {"cw": 50, "rb": 100}}})
    assert r.status_code == 200
    assert fake.writes == [("D1", {"onOff": 1, "Cold_white": 50, "Royal_blue": 1000})]
