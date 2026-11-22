import json
import unittest
import sys
import os

# Ensure engine is on python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "../..")))

from engine.worker import parse_profile_metadata, parse_posts_blitz

class TestWorker(unittest.TestCase):
    def test_parse_profile_metadata(self):
        sample_api_data = {
            "id": "12345678",
            "username": "target_user",
            "full_name": "Target Full Name",
            "biography": "Target Bio Text",
            "is_private": False,
            "followed_by_viewer": False,
            "edge_owner_to_timeline_media": {"count": 42},
            "profile_pic_url_hd": "https://cdn.example.com/pic.jpg"
        }
        parsed = parse_profile_metadata(sample_api_data)
        self.assertEqual(parsed["id"], "12345678")
        self.assertEqual(parsed["username"], "target_user")
        self.assertEqual(parsed["media_count"], 42)
        self.assertEqual(parsed["is_private"], False)

    def test_parse_posts_blitz(self):
        sample_node = {
            "id": "post_999",
            "shortcode": "C123456",
            "is_video": True,
            "display_url": "https://cdn.example.com/thumb.jpg",
            "video_url": "https://cdn.example.com/video.mp4",
            "taken_at_timestamp": 1700000000,
            "edge_media_to_caption": {"edges": [{"node": {"text": "Hello world!"}}]}
        }
        parsed_post = parse_posts_blitz(sample_node)
        self.assertEqual(parsed_post["shortcode"], "C123456")
        self.assertEqual(parsed_post["media_type"], "reel")
        self.assertIn("video.mp4", parsed_post["cdn_url"])
        self.assertEqual(parsed_post["caption"], "Hello world!")

if __name__ == "__main__":
    unittest.main()
