import json
import unittest
import sys
import os

# Ensure engine is on python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "../..")))

from engine.worker import parse_profile_metadata, parse_posts_blitz
from engine.instagram_client import (
    parse_cookie_text,
    parse_profile_and_posts_from_ssr_html,
    is_sponsored_or_foreign_node,
)

class TestWorker(unittest.TestCase):
    def test_parse_cookie_text_formats(self):
        raw = "sessionid=abc123; csrftoken=csrf999; ds_user_id=4242"
        parsed = parse_cookie_text(raw)
        self.assertEqual(parsed["sessionid"], "abc123")
        self.assertEqual(parsed["csrftoken"], "csrf999")
        self.assertEqual(parsed["ds_user_id"], "4242")

        as_json = parse_cookie_text('{"sessionid": "xyz", "csrftoken": "tok"}')
        self.assertEqual(as_json["sessionid"], "xyz")
        self.assertEqual(as_json["csrftoken"], "tok")

    def test_ssr_parser_extracts_counts_and_filters_ads(self):
        html = '''<html><body><script type="application/json">{"data":{"user":{"pk":"555","username":"ciculitaa","full_name":"Cicu","biography":"Bio","is_private":false,"follower_count":1250,"following_count":310,"media_count":18},"xdt_api__v1__feed__user_timeline_graphql_connection":{"edges":[{"node":{"code":"AD_1","ad_id":"99","user":{"username":"brand"}}},{"node":{"code":"REAL_1","boosted_status":"not_boosted","iab_autofill_optout_info":{"is_iab_autofill_optout":false},"injected":{},"user":{"username":"ciculitaa"},"display_uri":"https://cdn.example.com/1.jpg"}}]}}}</script></body></html>'''
        user = parse_profile_and_posts_from_ssr_html(html, "ciculitaa")
        self.assertIsNotNone(user)
        self.assertEqual(user["id"], "555")
        self.assertEqual(user["edge_followed_by"]["count"], 1250)
        self.assertEqual(user["edge_follow"]["count"], 310)
        self.assertEqual(len(user["edge_owner_to_timeline_media"]["edges"]), 1)
        self.assertEqual(user["edge_owner_to_timeline_media"]["edges"][0]["node"]["code"], "REAL_1")
        self.assertTrue(is_sponsored_or_foreign_node({"commerciality_status": "ads"}, "ciculitaa"))
        self.assertFalse(is_sponsored_or_foreign_node({"boosted_status": "not_boosted", "injected": {}, "user": {"username": "ciculitaa"}}, "ciculitaa"))

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
