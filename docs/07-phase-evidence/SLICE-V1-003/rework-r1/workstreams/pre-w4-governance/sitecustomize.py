"""Opt-in observational hook for the exact existing Makefile unittest command."""
import os
import sys
_EXPECTED = ['-m','unittest','discover','-s','tests','-p','test_*.py']
_original = getattr(sys, 'orig_argv', None)
# Python 3.9 does not expose orig_argv. At site initialization it leaves -m
# as argv[0] and removes the module name. The runner separately verifies that
# __main__.__spec__.name is unittest.__main__ before recording any results.
_exact = _original[1:] == _EXPECTED if _original is not None else sys.argv == [
    '-m','discover','-s','tests','-p','test_*.py']
if os.environ.get('SLICE3_NAMED_UNITTEST_OUTPUT') and _exact:
    from slice3_unittest_capture import install
    install()
