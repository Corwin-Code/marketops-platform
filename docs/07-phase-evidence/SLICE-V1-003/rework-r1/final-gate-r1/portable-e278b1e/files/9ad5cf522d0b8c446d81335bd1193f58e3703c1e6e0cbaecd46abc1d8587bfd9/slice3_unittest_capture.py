"""Observe standard unittest callbacks; do not change discovery or assertions."""
import collections
import hashlib
import inspect
import json
import os
from pathlib import Path
import sys
import time
import unittest
from datetime import datetime,timezone

def sha(raw):return hashlib.sha256(raw).hexdigest()
def now():return datetime.now(timezone.utc).isoformat()
def install():
    output=Path(os.environ['SLICE3_NAMED_UNITTEST_OUTPUT']).resolve()
    root=Path(os.environ['SLICE3_NAMED_REPOSITORY']).resolve()
    if output.is_relative_to(root):raise RuntimeError('Named result output must be outside repository')
    original_run=unittest.TextTestRunner.run
    startup_argv=list(sys.argv)
    original_argv=getattr(sys, 'orig_argv', None)
    sources={};depth=0
    def describe(test):
        method=getattr(test,'_testMethodName',None)
        entry={'unittestId':test.id(),'runtimeModule':type(test).__module__,'runtimeClass':type(test).__qualname__,'method':method}
        try:
            func=inspect.unwrap(getattr(type(test),method));p=Path(inspect.getsourcefile(func)).resolve()
            raw=p.read_bytes();lines,line=inspect.getsourcelines(func)
            relative=str(p.relative_to(root))
            sources.setdefault(relative,{'path':relative,'sha256Before':sha(raw)})
            entry.update(sourcePath=relative,sourceSha256=sha(raw),declaringCallable=func.__qualname__,sourceLine=line,methodSourceSha256=sha(''.join(lines).encode()))
        except (TypeError,AttributeError,OSError,ValueError):entry['sourceBinding']='FRAMEWORK_HOLDER_OR_DYNAMIC_SOURCE_REQUIRES_REVIEW'
        return entry
    class NamedResult(unittest.TextTestResult):
        def __init__(self,*args,**kwargs):
            super().__init__(*args,**kwargs);self.rows={};self.fixtureEvents=[];self.subtestOrdinal=0
        def row(self,test):
            parent=getattr(test,'test_case',None)
            if parent is not None:test=parent
            key=test.id()
            return self.rows.setdefault(key,{**describe(test),'status':'NOT_COMPLETED','subtests':[]})
        def startTest(self,test):
            super().startTest(test);r=self.row(test);r['startedAtUTC']=now();r['_startedMonotonic']=time.monotonic()
        def stopTest(self,test):
            r=self.row(test);r['finishedAtUTC']=now();r['elapsedSeconds']=time.monotonic()-r.pop('_startedMonotonic',time.monotonic())
            super().stopTest(test)
        def addSuccess(self,test):
            self.row(test)['status']='PASSED';super().addSuccess(test)
        def addFailure(self,test,err):
            self.row(test).update(status='FAILED',exceptionType=err[0].__name__);super().addFailure(test,err)
        def addError(self,test,err):
            r=self.row(test);r.update(status='ERROR',exceptionType=err[0].__name__)
            if not getattr(test,'_testMethodName',None):self.fixtureEvents.append({'id':test.id(),'status':'ERROR','exceptionType':err[0].__name__})
            super().addError(test,err)
        def addSkip(self,test,reason):
            r=self.row(test)
            if getattr(test,'test_case',None) is not None:r['subtests'].append({'ordinal':len(r['subtests'])+1,'status':'SKIPPED','parameters':'NOT_RECORDED'})
            else:r['status']='SKIPPED'
            super().addSkip(test,reason)
        def addExpectedFailure(self,test,err):
            self.row(test).update(status='EXPECTED_FAILURE',exceptionType=err[0].__name__);super().addExpectedFailure(test,err)
        def addUnexpectedSuccess(self,test):
            self.row(test)['status']='UNEXPECTED_SUCCESS';super().addUnexpectedSuccess(test)
        def addSubTest(self,test,subtest,err):
            r=self.row(test);status='PASSED' if err is None else 'FAILED' if issubclass(err[0],test.failureException) else 'ERROR'
            item={'ordinal':len(r['subtests'])+1,'status':status,'parameters':'NOT_RECORDED'}
            if err is not None:
                item['exceptionType']=err[0].__name__
                if r['status']!='ERROR':r['status']=status
            r['subtests'].append(item);super().addSubTest(test,subtest,err)
    def flatten(suite):
        if isinstance(suite,unittest.TestSuite):
            for item in suite:
                if item is not None:yield from flatten(item)
        else:yield suite
    def run(self,test):
        nonlocal depth
        main_spec=getattr(sys.modules.get('__main__'),'__spec__',None)
        if depth or getattr(main_spec,'name',None)!='unittest.__main__':return original_run(self,test)
        depth+=1;started=now();planned=[describe(t) for t in flatten(test)];result=None;failure=None
        try:
            result=original_run(self,test);return result
        except BaseException as exc:
            failure=type(exc).__name__;raise
        finally:
            rows=list(result.rows.values()) if result is not None else []
            for r in rows:
                if r['status']=='PASSED' and any(s['status']=='SKIPPED' for s in r['subtests']):r['status']='PASSED_WITH_SKIPPED_SUBTESTS'
            for info in sources.values():
                p=root/info['path'];info['sha256After']=sha(p.read_bytes()) if p.is_file() else None;info['stable']=info['sha256Before']==info['sha256After']
            payload={'kind':'ACTUAL_NAMED_UNITTEST_RESULTS_NOT_CRITERION_ASSESSMENT','startedAtUTC':started,'finishedAtUTC':now(),'argv':list(getattr(sys,'orig_argv',sys.argv)),'argvBasis':'INTERPRETER_ORIGINAL_ARGV' if original_argv is not None else 'RUNTIME_MODULE_ARGV_WITH_SEPARATE_OBSERVED_STARTUP_ARGV','startupArgv':startup_argv,'mainModuleSpec':getattr(main_spec,'name',None),'cwd':str(root),
                     'pythonVersion':sys.version,'pythonExecutable':sys.executable,'discoveredCount':len(planned),'discovered':planned,'methodsAndFrameworkEvents':rows,
                     'sources':list(sources.values()),'allRecordedSourcesStable':all(s['stable'] for s in sources.values()),'frameworkFixtureEvents':result.fixtureEvents if result else [],
                     'frameworkCounts':{'testsRun':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped),'expectedFailures':len(result.expectedFailures),'unexpectedSuccesses':len(result.unexpectedSuccesses)} if result else None,
                     'methodOutcomeCounts':dict(collections.Counter(r['status'] for r in rows)),'frameworkWasSuccessful':result.wasSuccessful() if result else False,
                     'unexecutedIds':sorted(set(r['unittestId'] for r in planned)-set(r['unittestId'] for r in rows)),'runnerExceptionType':failure,
                     'assessment':'NOT_PERFORMED','controllerVerdict':'NOT_ISSUED','boundary':'Subtest parameters, exception messages and locals are omitted; standard raw make output is preserved separately. Framework test counts and subtest outcomes are distinct.'}
            output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n');depth-=1
    unittest.TextTestRunner.resultclass=NamedResult
    unittest.TextTestRunner.run=run
