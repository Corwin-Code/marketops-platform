from __future__ import annotations

import unittest

from scripts.collect_supply_chain import licence_lines


class FrontendLicenceInventoryTests(unittest.TestCase):
    def test_uninstalled_optional_placeholders_are_not_inventory_items(self) -> None:
        tree = {
            "dependencies": {
                "installed": {
                    "version": "1.2.3",
                    "license": "MIT",
                    "dependencies": {"missing-optional": {}},
                },
                "another-missing-optional": {},
            }
        }

        self.assertEqual(["installed@1.2.3\tMIT"], licence_lines(tree))

    def test_an_installed_package_without_a_declared_licence_remains_visible(self) -> None:
        tree = {"dependencies": {"installed": {"version": "1.2.3"}}}

        self.assertEqual(["installed@1.2.3\tUNDECLARED"], licence_lines(tree))


if __name__ == "__main__":
    unittest.main()
