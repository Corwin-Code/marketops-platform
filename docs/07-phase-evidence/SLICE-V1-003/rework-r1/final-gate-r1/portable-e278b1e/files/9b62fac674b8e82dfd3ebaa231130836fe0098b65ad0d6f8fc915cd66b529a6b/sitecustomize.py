"""Observe only this exact isolated binder command; never widen make's hook."""
import os
import sys
_EXPECTED = ['-m', 'unittest', 'discover', '-s', '/Users/chzhengx/Code/personal/marketops-platform/docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/assessment_tools', '-p', 'test_*.py', '-v']
_original = getattr(sys, 'orig_argv', None)
# Python 3.9 site startup retains -m but removes the unittest module token.
_exact = _original[1:] == _EXPECTED if _original is not None else sys.argv == [_EXPECTED[0], *_EXPECTED[2:]]
if os.environ.get('SLICE3_NAMED_UNITTEST_OUTPUT') and _exact:
    from slice3_unittest_capture import install
    install()
