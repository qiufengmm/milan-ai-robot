import importlib.util
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("setup_demo", Path(__file__).with_name("setup_demo.py"))
SETUP_DEMO = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SETUP_DEMO)


class FakeApi:
    def __init__(self):
        self.calls = []

    def data(self, method, path, payload=None):
        self.calls.append((method, path, payload))


class ExistingVersionReconciliationTest(unittest.TestCase):
    def test_archived_version_stays_archived_when_seed_is_rerun(self):
        api = FakeApi()
        status = SETUP_DEMO.ensure_existing_version_published(
            api,
            {"id": 30},
            {"id": 31, "publishStatus": "ARCHIVED", "status": 3, "indexStatus": "SYNCED"},
        )

        self.assertEqual("ARCHIVED", status)
        self.assertEqual([], api.calls)

    def test_ready_draft_is_published_once(self):
        api = FakeApi()
        status = SETUP_DEMO.ensure_existing_version_published(
            api,
            {"id": 30},
            {"id": 31, "publishStatus": "DRAFT", "status": 3, "indexStatus": "SYNCED"},
        )

        self.assertEqual("PUBLISHED", status)
        self.assertEqual(
            [("POST", "/customer-service/documents/30/publish", {"fileId": 31})],
            api.calls,
        )

    def test_not_ready_draft_is_not_published(self):
        api = FakeApi()
        with self.assertRaises(RuntimeError):
            SETUP_DEMO.ensure_existing_version_published(
                api,
                {"id": 30},
                {"id": 31, "publishStatus": "DRAFT", "status": 2, "indexStatus": "PENDING"},
            )

        self.assertEqual([], api.calls)


if __name__ == "__main__":
    unittest.main()
