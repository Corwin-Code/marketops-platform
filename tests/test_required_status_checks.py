import copy
import unittest

from scripts import validate_governance as validator


class RequiredStatusChecksTests(unittest.TestCase):
    def test_inventory_requires_infrastructure_validation(self):
        inventory = {
            "strictRequiredStatusChecksPolicy": True,
            "requiredContexts": sorted(validator.REQUIRED_STATUS_CONTEXTS),
        }
        errors = []
        validator.validate_required_status_inventory(errors, inventory)
        self.assertEqual([], errors)

        mutated = copy.deepcopy(inventory)
        mutated["requiredContexts"].remove("infrastructure-validation")
        errors = []
        validator.validate_required_status_inventory(errors, mutated)
        self.assertTrue(any("infrastructure-validation" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
