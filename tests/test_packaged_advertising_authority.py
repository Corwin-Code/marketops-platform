"""A packaged test-only actor or synthetic SQL oracle must fail certification."""
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "migration_artifact", Path(__file__).resolve().parents[1] / "scripts/verify_migration_artifact.py")
artifact = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(artifact)


class PackagedAdvertisingAuthorityTest(unittest.TestCase):
    def test_production_advertising_code_and_canonical_migrations_remain_valid(self):
        artifact.reject_packaged_test_authority([
            "BOOT-INF/classes/com/mimococo/marketops/advertisingefficiency/AdvertisingBriefView.class",
            "BOOT-INF/classes/db/migration/V0065__route_settled_advertising_contradictions_to_finance_review.sql"])

    def test_each_test_authority_is_refused_even_alongside_valid_production_code(self):
        for name in ("BrowserFixtureApplication.class", "BrowserSigningFixture$Keys.class",
                     "AdvertisingR1Fixture.class", "AdvertisingBrowserHistorySeed.class",
                     "AdvertisingManualBrowserSeed.class", "r1-fictional-positive.sql"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "must never enter"):
                artifact.reject_packaged_test_authority([
                    "BOOT-INF/classes/com/mimococo/marketops/MarketOpsServerApplication.class",
                    "BOOT-INF/classes/test-fixture/" + name])
