import json
import unittest

from engine.comments_extractor import (
    CommentRecord,
    build_comment_tree,
    export_comments,
    query_comment_tree,
    shortcode_to_media_id,
)


class TestCommentsExtractor(unittest.TestCase):
    def _sample(self):
        return [
            CommentRecord(
                comment_id="1001",
                shortcode="CxTEST12345",
                media_id="320001",
                parent_comment_id=None,
                author_username="alice",
                author_id="10",
                is_verified=True,
                text="Excellent post!",
                created_at="2026-11-25T01:00:00Z",
                like_count=120,
                reply_count=2,
            ),
            CommentRecord(
                comment_id="1002",
                shortcode="CxTEST12345",
                media_id="320001",
                parent_comment_id="1001",
                author_username="bob",
                author_id="11",
                text="Totally agree on the cinematography.",
                created_at="2026-11-25T01:05:00Z",
                like_count=14,
            ),
            CommentRecord(
                comment_id="1003",
                shortcode="CxTEST12345",
                media_id="320001",
                parent_comment_id="1002",
                author_username="carol",
                author_id="12",
                text="Same here!",
                created_at="2026-11-25T01:06:00Z",
                like_count=3,
            ),
            CommentRecord(
                comment_id="2001",
                shortcode="CxTEST12345",
                media_id="320001",
                parent_comment_id=None,
                author_username="zara",
                author_id="13",
                text="First!",
                created_at="2026-11-25T02:00:00Z",
                like_count=5,
            ),
        ]

    def test_shortcode_to_media_id(self):
        self.assertEqual(shortcode_to_media_id("B"), "1")
        mid = shortcode_to_media_id("CxTEST12345")
        self.assertIsNotNone(mid)
        self.assertTrue(mid.isdigit())

    def test_tree_and_search(self):
        tree = build_comment_tree(self._sample())
        self.assertEqual(len(tree), 2)
        c1 = next(t for t in tree if t.comment.comment_id == "1001")
        self.assertEqual([r.comment_id for r in c1.replies], ["1002", "1003"])

        found = query_comment_tree(self._sample(), search="cinematography")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].comment.comment_id, "1001")
        self.assertEqual([r.comment_id for r in found[0].replies], ["1002"])

        newest = query_comment_tree(self._sample(), sort_by="newest")
        self.assertEqual([t.comment.comment_id for t in newest], ["2001", "1001"])

    def test_export_formats(self):
        threads = build_comment_tree(self._sample())
        raw_json = export_comments(threads, shortcode="CxTEST12345", target_username="target", fmt="json")
        parsed = json.loads(raw_json)
        self.assertEqual(parsed["collection_status"], "complete")
        self.assertEqual(parsed["stats"]["total_comments"], 4)
        self.assertEqual(len(parsed["comments"][0]["replies"]), 2)

        raw_csv = export_comments(threads, shortcode="CxTEST12345", fmt="csv")
        self.assertIn("comment_id,parent_comment_id,depth", raw_csv)
        self.assertIn("1002,1001,1,bob", raw_csv)

        raw_txt = export_comments(threads, shortcode="CxTEST12345", fmt="txt")
        self.assertIn("├─ @bob", raw_txt)
        self.assertIn("└─ @carol", raw_txt)

        raw_md = export_comments(threads, shortcode="CxTEST12345", fmt="md")
        self.assertIn("### @alice", raw_md)
        self.assertIn("> **@bob**", raw_md)


if __name__ == "__main__":
    unittest.main()
