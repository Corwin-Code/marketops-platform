/**
 * Arrangements written to make the architecture rules fail, and one written to
 * make them pass.
 *
 * <p>Two of the rules accept an empty subject set, which is the right answer for
 * a tree that legitimately contains no web resource, and which would otherwise
 * let a rule report success without having examined anything. Each fixture here
 * breaks exactly one rule, so the rule is observed failing at least once per
 * build and cannot quietly stop working.
 *
 * <p>Nothing in this package is wired into the application. The classes exist to
 * be compiled and read by the importer.
 */
package com.mimococo.marketops.testfixture;
