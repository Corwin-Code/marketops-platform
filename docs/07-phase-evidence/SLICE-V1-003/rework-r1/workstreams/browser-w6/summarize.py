import ast,datetime,hashlib,json,pathlib,re,subprocess
r=pathlib.Path('/tmp/slice3-w6-browser-evidence');repo=pathlib.Path('/Users/chzhengx/Code/personal/marketops-platform')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def ref(p):return {'path':str(p),'sha256':sha(p),'bytes':p.stat().st_size}
ad=json.loads((r/'browser-r6/receipt.json').read_text());le=json.loads((r/'legacy-browser-r2/receipt.json').read_text())
assert ad['sourceCommit']==le['sourceCommit']=='3ed3f4c87c336cb07188e470528f328358fb279f'
assert ad['sourceTree']==le['sourceTree']=='4e73aa0e7c30fe470528ecd5287b55a9c55e5ff1'
for kind,receipt in [('browser-r6',ad),('legacy-browser-r2',le)]:
 n=json.loads((r/kind/'named-browser-results.json').read_text()); assert len(n['tests'])=={'browser-r6':12,'legacy-browser-r2':25}[kind]
 assert all(t['outcome']=='expected' and all(a['status']=='passed' for a in t['attempts']) for t in n['tests'])
for a in ad['artifacts']:assert sha(pathlib.Path(a['path']))==a['sha256']
sb=(r/'legacy-browser-r2/logs/10-command.log').read_text();assert 'skipped validating BOM' not in sb
head=subprocess.check_output(['git','rev-parse','HEAD','HEAD^{tree}'],cwd=repo,text=True).splitlines();status=subprocess.check_output(['git','status','--porcelain'],cwd=repo,text=True)
assert head==[ad['sourceCommit'],ad['sourceTree']] and not status
shots=r/'browser-r6/screenshots';vr=r/'visual-review'
review=[
 ('maker-minimum-disclosure.png','Full screenshot inspected. Maker financial fields/cause/factors are MASKED while exact native bid and structural affected members remain. No observed overlap or clipped identity/value in this viewport.'),
 ('owner-exact-decision-evidence.png','Top and bottom viewport derivatives inspected from the 38694-pixel full screenshot. Authorized financial detail and exact submitted native bid/unit/step/profile are readable; unresolved supporting inputs stay explicit. Middle pages are not claimed visually inspected.'),
 ('WILDBERRIES-independent-proof-early-safety-pending.png','Top and bottom derivatives inspected. Placement/CURRENCY_MINOR/step5/precision0/derived readback remain UNVERIFIED synthetic; early safety not due and unresolved sales preservation remain visible, with no business-success assertion.'),
 ('OZON-independent-proof-early-safety-pending.png','Top derivative inspected. Keyword/CURRENCY_MAJOR/step0.5/precision2/exact-field semantics remain UNVERIFIED synthetic, visibly different from Wildberries.'),
 ('HISTORY_REGRESSION-actual-http-history.png','Bottom derivative inspected. Earlier Settled reading and later regression/restatement both persist; synthetic read-oracle and computationEvidence=false are explicit, with exact-prior-bid compensation separate.'),
 ('keyboard-page-two-six-visible-cases.png','Full screenshot inspected. Page2 contains six distinct visible Watch rows, previous enabled/next disabled. Financial fields remain masked. This image supplements actual HTTP and keyboard assertions, not independent pagination arithmetic.'),
 ('keyboard-acknowledgement-not-action.png','Full screenshot inspected. Acknowledgement control and active action responsibility remain distinct; screenshot supplements the actual API assertion acknowledgedAt!=null and firstAttributableActionAt==null.'),
 ('maker-native-unknown-history-masked.png','Full screenshot inspected. Native UNKNOWN_REQUIRES_READBACK history remains visible, economics masked, absent outcome explicit, and compensation separate; no absent outcome becomes neutral or healthy.')]
visual=[]
for name,note in review:
 p=list(shots.rglob(name));assert len(p)==1;visual.append({'original':ref(p[0]),'observation':note})
visual_doc={'recordedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'originalScreenshotCount':len(list(shots.rglob('*.png'))),'representativeOriginalsInspected':len(visual),'derivedViewportIndex':ref(vr/'viewport-index.json'),'observations':visual,'limits':['Visual inspection is representative, not all screenshot pixels. Original files are untouched; sips derivatives only support inspection.','Legacy success paths use screenshot only-on-failure; 25 passed and emitted zero screenshots. No legacy visual screenshot review is claimed.','Synthetic lane/history rows establish actual HTTP rendering and scope behavior only, not canonical economic classification, Provider responses or outcome calculation truth.']}
(vr/'review.json').write_text(json.dumps(visual_doc,ensure_ascii=False,indent=2)+'\n')
summary={'recordedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceCommit':head[0],'sourceTree':head[1],'repositoryTrackedAndUntrackedStatus':'CLEAN','advertising':{'receipt':ref(r/'browser-r6/receipt.json'),'namedTests':ref(r/'browser-r6/named-browser-results.json'),'passed':12,'failed':0,'skipped':0,'screenshots':26,'syntheticScenarioRoleIdentities':27,'sourceUnchanged':ad['sourceUnchanged'],'startedAt':ad['startedAt'],'finishedAt':ad['finishedAt'],'durationSeconds':ad['durationSeconds'],'resources':ref(r/'browser-r6/runtime-resources.json'),'sourceManifest':ref(r/'browser-r6/source-inputs.json')},'legacy':{'receipt':ref(r/'legacy-browser-r2/receipt.json'),'namedTests':ref(r/'legacy-browser-r2/named-browser-results.json'),'passed':25,'failed':0,'skipped':0,'screenshots':0,'originalTrackedInputsUnchanged':le['originalTrackedInputsUnchanged'],'oldBaselineDelta':ref(r/'legacy-browser-r2/legacy-test-input-delta.json'),'startedAt':le['startedAt'],'finishedAt':le['finishedAt'],'durationSeconds':le['durationSeconds'],'resources':ref(r/'legacy-browser-r2/runtime-resources.json'),'sourceManifest':ref(r/'legacy-browser-r2/source-inputs.json')},'frontendQuality':le['frontendQuality']|{'actualSbomSchemaValidation':'Command includes --validate; exit0; no skip-validator warning; fresh optional validators installed.','sbomCommandLog':ref(r/'legacy-browser-r2/logs/10-command.log')},'visualReview':ref(vr/'review.json'),'cleanup':{'legacy':{'project':le['database']['composeProject'],'exitCode':le['cleanupExitCode'],'observedLog':'runtime.log explicitly records own container, volume and network Removed. Private archive also removed by wrapper.','log':ref(r/'legacy-browser-r2/runtime.log')},'advertising':{'scriptCompletedWithExit':0,'scope':'Only fresh mktemp namespace/container/network owned by advertising_browser_isolated.sh','limitation':'EXIT trap invokes exact-name container/network deletion and private directory removal. Cleanup commands suppress errors and the random namespace was not included in raw log, so this receipt does not claim independent post-run Docker inventory verification.'},'activeBrowserMavenSessionsOwnedByThisAgent':0},'engineeringBoundary':'Local W6 tests passed. Root final clean backend still running; exact latest CI not passed (W6 frontend-test has a separate diagnosed old global-alert locator failure). No AC approval, independent Controller verdict, real Provider access or production enablement is inferred.','pendingRemoteFailure':ref(pathlib.Path('/tmp/slice3-w6-frontend-ci-repair/root-cause.json'))}
(r/'verification-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
# Exact repository publication-secret expressions, plus structural token value checks.
p=repo/'scripts/validate_governance.py';source=p.read_text();patterns=None
for n in ast.parse(source).body:
 if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='SECRET_PATTERNS' for t in n.targets):patterns=eval(compile(ast.Expression(n.value),'<exact-secret-patterns>','eval'),{'re':re})
assert patterns is not None
files=[];matches=[]
extra=[re.compile(r'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}'),re.compile(r'(?i)Bearer\s+[A-Za-z0-9_.-]{20,}')]
for p in sorted(r.rglob('*')):
 if not p.is_file() or p.suffix not in {'.json','.log','.ts','.md','.txt'} or p.name=='publication-scan.json':continue
 text=p.read_text();hits=[i for i,rx in enumerate(patterns+extra) if rx.search(text)]
 files.append(ref(p));
 if hits:matches.append({'path':str(p),'patternIndexes':hits})
scan={'recordedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Textual evidence in /tmp/slice3-w6-browser-evidence, including raw logs, named results and SBOM; no binary image content scan or general data-classification claim.','scannerSource':ref(repo/'scripts/validate_governance.py'),'exactSecretPatternCount':len(patterns),'additionalJwtBearerPatterns':2,'files':files,'findings':matches,'passed':not matches,'limits':'No copied raw trace/HAR or browser request headers. Filenames and SHA only are emitted for matches; this check cannot prove absence of every conceivable secret/PII.'}
(r/'publication-scan.json').write_text(json.dumps(scan,indent=2)+'\n');print(json.dumps({'summary':ref(r/'verification-summary.json'),'visualReview':ref(vr/'review.json'),'scan':ref(r/'publication-scan.json'),'scanPassed':not matches,'matches':matches},indent=2))
