import tempfile
import unittest
from pathlib import Path

from engine.providers import (
    DownloadOutcome,
    DownloadStatus,
    ProviderCapability,
    PythonProviderOrchestrator,
    StagedAsset,
    YtDlpProvider,
    is_allowed_media_url,
    sanitize_log,
    validate_media_file,
)


class _StubProvider:
    def __init__(self, pid: str, outcome_fn, available: bool = True):
        self.provider_id = pid
        self.capabilities = {ProviderCapability.PHOTO_POST, ProviderCapability.VIDEO_POST}
        self._outcome_fn = outcome_fn
        self._available = available
        self.calls = 0

    def is_available(self) -> bool:
        return self._available

    def download_post(self, shortcode, media_type_hint, staging_dir, cdn_url="", cookie_file=None):
        self.calls += 1
        return self._outcome_fn(shortcode, staging_dir)


def _write_fake_jpg(path: Path, payload: bytes = b"A" * 256) -> Path:
    data = b"\xff\xd8\xff\xe0" + payload
    path.write_bytes(data)
    return path


def _write_fake_mp4(path: Path, payload: bytes = b"B" * 256) -> Path:
    data = b"\x00\x00\x00\x18ftypisom" + payload
    path.write_bytes(data)
    return path


class TestPythonProviders(unittest.TestCase):
    def test_security_helpers(self):
        self.assertTrue(is_allowed_media_url("https://scontent.cdninstagram.com/v/photo.jpg"))
        self.assertFalse(is_allowed_media_url("http://scontent.cdninstagram.com/v/photo.jpg"))
        self.assertFalse(is_allowed_media_url("https://evil.example.com/photo.jpg"))

        redacted = sanitize_log("sessionid=MY_SECRET; csrftoken=CSRF_TOK https://a.fbcdn.net/x?oh=TOKEN123")
        self.assertNotIn("MY_SECRET", redacted)
        self.assertNotIn("CSRF_TOK", redacted)
        self.assertNotIn("TOKEN123", redacted)

    def test_validate_media_file_magic_bytes_and_html_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)
            jpg = _write_fake_jpg(p / "ok.jpg")
            res = validate_media_file(jpg)
            self.assertIsNotNone(res)
            self.assertEqual(res[0], "image/jpeg")
            self.assertFalse(res[2])
            self.assertEqual(len(res[4]), 64)

            mp4 = _write_fake_mp4(p / "ok.mp4")
            res_vid = validate_media_file(mp4)
            self.assertIsNotNone(res_vid)
            self.assertEqual(res_vid[0], "video/mp4")
            self.assertTrue(res_vid[2])

            html_fake = p / "login.jpg"
            html_fake.write_text("<!DOCTYPE html><html><body>Login</body></html>" + "x" * 200)
            self.assertIsNone(validate_media_file(html_fake))

    def test_ytdlp_error_classification(self):
        out1 = YtDlpProvider.classify_error("ERROR: There is no video in this post")
        self.assertEqual(out1.status, DownloadStatus.UNSUPPORTED_MEDIA)
        out2 = YtDlpProvider.classify_error("HTTP Error 429: Too Many Requests")
        self.assertEqual(out2.status, DownloadStatus.RATE_LIMITED)
        out3 = YtDlpProvider.classify_error("Instagram sent an empty media response")
        self.assertEqual(out3.status, DownloadStatus.LOGIN_REQUIRED)

    def test_orchestrator_fallback_circuit_breaker_and_sha256_dedup(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)

            # yt-dlp returns RateLimited -> falls back to gallery-dl -> succeeds
            yt_stub = _StubProvider(
                "yt-dlp",
                lambda sc, st: DownloadOutcome(status=DownloadStatus.RATE_LIMITED, provider_id="yt-dlp", retry_after_seconds=60),
            )

            def gdl_ok(sc, st: Path):
                f = _write_fake_mp4(st / f"{sc}.mp4", b"SAME_CONTENT" * 30)
                val = validate_media_file(f)
                return DownloadOutcome(
                    status=DownloadStatus.SUCCESS,
                    provider_id="gallery-dl",
                    assets=[StagedAsset(position=0, media_type="video", file_path=f, mime_type=val[0], file_size=val[3], sha256=val[4])],
                )

            gdl_stub = _StubProvider("gallery-dl", gdl_ok)
            http_stub = _StubProvider("instaloader-http", lambda sc, st: DownloadOutcome(status=DownloadStatus.ERROR, provider_id="instaloader-http"))

            orch = PythonProviderOrchestrator(ytdlp=yt_stub, gallerydl=gdl_stub, http_provider=http_stub)
            sha_index = {}

            ok1, prov1, recs1 = orch.hydrate_item("SC1", "reel", out_dir / "SC1.mp4", sha_index=sha_index)
            self.assertTrue(ok1)
            self.assertEqual(prov1, "gallery-dl")
            self.assertEqual(yt_stub.calls, 1)
            # Circuit breaker should now block yt-dlp on immediate second call
            self.assertFalse(orch.can_use(yt_stub))

            ok2, prov2, recs2 = orch.hydrate_item("SC2", "reel", out_dir / "SC2.mp4", sha_index=sha_index)
            self.assertTrue(ok2)
            self.assertEqual(yt_stub.calls, 1)  # Skipped because on cooldown!
            # SHA-256 deduplication reused SC1.mp4 path instead of duplicating identical bytes
            self.assertEqual(recs1[0]["file_path"], recs2[0]["file_path"])


if __name__ == "__main__":
    unittest.main()
