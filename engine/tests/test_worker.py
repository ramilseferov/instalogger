import json
import unittest
import sys
import os

# Ensure engine is on python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "../..")))

from engine.worker import parse_profile_metadata, parse_posts_blitz, compute_avatar_dhash, dhash_hamming_distance
from engine.instagram_client import (
    parse_cookie_text,
    parse_profile_and_posts_from_ssr_html,
    parse_highlights_from_ssr_html,
    parse_reels_media_json,
    parse_stories_from_ssr_html,
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

    def test_ssr_highlights_and_reels_media_parsers(self):
        hl_html = '''<script type="application/json">{"data":{"lox_highlights_connection":{"edges":[{"node":{"id":"1790001","title":"Travel","cover_media_cropped_thumbnail_url":"https://cdn.example.com/cov.jpg","media_count":5}}]}}}</script>'''
        trays = parse_highlights_from_ssr_html(hl_html)
        self.assertEqual(len(trays), 1)
        self.assertEqual(trays[0]["id"], "highlight:1790001")
        self.assertEqual(trays[0]["title"], "Travel")

        # Format A: reels map
        stories_a = parse_reels_media_json({
            "reels": {
                "8488698886": {
                    "items": [
                        {"pk": "9001", "media_type": 2, "video_versions": [{"url": "https://cdn.example.com/s1.mp4"}], "taken_at": 1700000100},
                        {"pk": "9002", "media_type": 1, "image_versions2": {"candidates": [{"url": "https://cdn.example.com/s2.jpg"}]}, "taken_at": 1700000200},
                    ]
                }
            }
        }, reel_key="8488698886")
        self.assertEqual(len(stories_a), 2)
        self.assertEqual(stories_a[0]["media_type"], "video")
        self.assertEqual(stories_a[1]["media_type"], "image")

        # SSR stories connection
        story_html = '''<script type="application/json">{"xdt_api__v1__feed__reels_media__connection":{"edges":[{"node":{"items":[{"pk":"777","display_url":"https://cdn.example.com/st.jpg","taken_at":1700000500}]}}]}}</script>'''
        stories_ssr = parse_stories_from_ssr_html(story_html)
        self.assertEqual(len(stories_ssr), 1)
        self.assertEqual(stories_ssr[0]["id"], "777")

    def test_ssr_extracts_page_info_and_og_media_count(self):
        html = '''<html><head><meta property="og:description" content="97M Followers, 80 Following, 4,512 Posts - NASA"/></head><body><script type="application/json">{"user":{"pk":"528817151","username":"nasa","full_name":"NASA"},"polaris_ordered_timeline_connection":{"page_info":{"has_next_page":true,"end_cursor":"CUR_NEXT_2"},"edges":[{"node":{"code":"NASA1","user":{"username":"nasa"},"display_uri":"https://cdn.example.com/n1.jpg"}}]}}</script></body></html>'''
        u = parse_profile_and_posts_from_ssr_html(html, "nasa")
        self.assertIsNotNone(u)
        self.assertEqual(u["edge_owner_to_timeline_media"]["count"], 4512)
        self.assertEqual(u["edge_owner_to_timeline_media"]["page_info"]["end_cursor"], "CUR_NEXT_2")

    def test_avatar_dhash_deduplicates_reencoded_images(self):
        import io
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (128, 128), (30, 60, 120))
        draw = ImageDraw.Draw(img)
        draw.ellipse((24, 24, 104, 104), fill=(240, 210, 180))
        b1, b2 = io.BytesIO(), io.BytesIO()
        img.save(b1, format="JPEG", quality=95)
        img.resize((64, 64)).save(b2, format="JPEG", quality=60)
        h1 = compute_avatar_dhash(b1.getvalue())
        h2 = compute_avatar_dhash(b2.getvalue())
        self.assertIsNotNone(h1)
        self.assertIsNotNone(h2)
        dist = dhash_hamming_distance(h1, h2)
        self.assertIsNotNone(dist)
        self.assertLessEqual(dist, 8)

if __name__ == "__main__":
    unittest.main()
