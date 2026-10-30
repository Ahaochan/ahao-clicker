"""Freeze partial P evidence, the unchanged formal denominator, and local hashes."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def digest(path):
    value=hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda:source.read(1024*1024),b''):value.update(chunk)
    return value.hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run',type=Path)
    args=parser.parse_args();root=args.run
    protocol=read(Path('docs/performance/protocol-v3.json'))
    groups=[dict(**g,status='not_started',actualFormalTasks=0,actualFormalLocalPoints=0,actualFormalExternalPoints=0,
                 reason='P_not_passed; do_not_replace_with_pilots_or_history') for g in protocol['groups']]
    write(root/'coverage.json',dict(groups=groups,formalTasksPlanned=sum(g.get('plannedTasks',0) for g in groups),formalTasksAttempted=0,
        fixedLocalPointsPlanned=sum(g.get('repeats',1)*g.get('plannedLocalPointsPerRepeat',0) for g in groups),
        fixedExternalPointsPlanned=sum(g.get('repeats',1)*g.get('plannedOraclePointsPerRepeat',0) for g in groups)))
    artifacts=root/'measurement-artifacts'
    if digest(artifacts/'measured.apk')!=digest(root/'pilot-ui-03/measured.apk'):raise RuntimeError('Measured app identity differs')
    shutil.copyfile(root/'pilot-ui-03/mapping.txt',artifacts/'app-mapping.txt')
    target=Path('tools/acceptance-target/build/outputs/apk/benchmark/acceptance-target-benchmark.apk')
    if digest(artifacts/'target.apk')!=digest(target):raise RuntimeError('Cannot archive mapping from a different target APK')
    shutil.copyfile(Path('tools/acceptance-target/build/outputs/mapping/benchmark/mapping.txt'),artifacts/'target-mapping.txt')
    write(artifacts/'identity.json',dict(appSha256=digest(artifacts/'measured.apk'),targetSha256=digest(artifacts/'target.apk'),
        appMappingSha256=digest(artifacts/'app-mapping.txt'),targetMappingSha256=digest(artifacts/'target-mapping.txt'),
        appliesTo=['pilot-frames-02','pilot-frames-03'],origin='Pulled installed APKs before restoration; mappings matched against those exact APK files.',
        earlierTargetMappingMissing=['pilot-ui-01','pilot-ui-02','pilot-ui-03','pilot-source-01'],
        finalRebuiltAppWasNotUsedForMeasurement=True))
    pilot_data=[]
    for path in sorted(root.glob('pilot-*/offline/summary.json')):
        value=read(path)
        pilot_data.append(dict(id=value['id'],collectorCompleted=value['collectorCompleted'],
            qualityErrors=value['qualityErrors'],draws=value['completeDrawFrames'],validArmedTaskChains=len(value['taskChains']),
            formal=False,summary=str(path.relative_to(root))))
    restoration=read(root/'restoration/result.json')
    replay=read(root/'history-replay/replay-index.json')
    history=read(root/'offline/index.json')['historical']
    quality=dict(protocolVersion='timing-v3',designAdmission='passed_frozen_unchanged',
        measuredAdmission='not_passed',formalCollection='not_started',formalDataComplete=False,
        analysisForExecutedPilotsAndHistory='completed',
        gates=dict(publicFlow='partial_one_supported_trial; arbitrary_millisecond_scroll_not_ready',
            reference='screening_40_valid_requests_20_compatible_pairs_zero_width_le_50ms; no_task_level_P_pass',
            frames='failed_after_three_pilots; parser_errors_ambiguity_and_incomplete_timebase',
            B_C_W='not_validated_current_build',overhead='not_run; no_single_record_and_paired_mode_admission',
            longFrameAndWait='not_run',offline='partial_actual_replay_passed; full_controlled_fault_suite_not_implemented',
            recovery='passed_exact_original_APK_and_preferences; service_bound; zero_trace_sessions'),
        collectorLimitations=['Initial UI pilots lack archived target mapping and per-pilot full immutable source manifest.',
            'Two frame pilots lack paired overhead baseline, complete cross-clock snapshots, host emulator CPU/GPU and independent external reference.',
            'During frames-02 a small offline unit test ran on the host; it is not an uncontaminated performance baseline.',
            'Clock-only results contained a stale prior terminal summary; raw events show no new arm. Reset fixed after capture, not retroactively rewritten.',
            'Header JSON formatting runs on the source worker; complete critical-region and paired-overhead compliance is not validated.',
            'RING_BUFFER is a diagnostic config deviation from frozen DISCARD, not an authorized formal protocol replacement.'],
        pilots=pilot_data,restoration=restoration,
        history=dict(results=history['batches'],recordedClickRows=history['clickRows'],recordedClockRows=history['clockRows'],measuredApkHashes=history['apkRevisions'],
            tracesReplayed=len(replay),tracesReplayedSuccessfully=sum(x['replayed'] for x in replay),
            traceHashesVerified=all(x.get('traceHashMatches') for x in replay),pooledPercentiles=False))
    write(root/'quality-and-admission.json',quality)
    write(root/'run-manifest.json',dict(phase='partial_P_and_offline_analysis',formal=False,
        authorization=dict(execution='User explicitly requested analysis under V3.',
            measurementChanges='User explicitly allowed measurement/test tools and optional hooks.',productionBehaviorOrAlgorithmChanges='forbidden'),
        frozenPlanSha256=digest(Path('docs/PERFORMANCE_ANALYSIS_PLAN.md')),
        frozenProtocolSha256=digest(Path('docs/performance/protocol-v3.json')),
        fullFormalGoalComplete=False,performance50msCertified=False,
        sourceAudit=digest(root/'measurement-scope-audit.json'),
        eventDefinitionSha256=digest(Path('core/src/main/kotlin/com/ahao/clicker/core/TimingObservation.kt')),
        analyzerSha256=digest(Path('tools/profiling/v3_analysis.py')),
        processorSha256=digest(Path('.tools/perfetto-analysis/bin/trace_processor.exe')),
        measuredArtifacts='measurement-artifacts/identity.json',quality='quality-and-admission.json',coverage='coverage.json'))
    excluded={'bundle-index.json','bundle-index.sha256','finalization.txt','bundle-verification.txt','final-content-validation.txt'}
    files=[]
    for path in sorted(root.rglob('*')):
        if not path.is_file() or (path.parent==root and path.name in excluded):continue
        files.append(dict(path=path.relative_to(root).as_posix(),bytes=path.stat().st_size,sha256=digest(path)))
    write(root/'bundle-index.json',dict(files=files,localArchive=True,privateBackupNotForGit=True,
        filesIndexed=len(files),totalBytes=sum(f['bytes'] for f in files),excludedControlOutputs=sorted(excluded)))
    index_hash=digest(root/'bundle-index.json');(root/'bundle-index.sha256').write_text(index_hash+'\n',encoding='ascii')
    public=dict(date='2026-10-28',protocolVersion='timing-v3',scope='existing_emulator_only',
        productionBehaviorOrAlgorithmsChanged=False,measurementToolsAndOptionalHooksChanged=True,
        designAdmission='passed',measuredAdmission='not_passed',formalCollection='not_started',
        completeV3Collection=False,performance50msCertified=False,
        artifactRoot=root.as_posix(),rawIndex='bundle-index.json',rawIndexSha256=index_hash,
        localFilesIndexed=len(files),localBytesIndexed=sum(f['bytes'] for f in files),
        executedLocalPilotSessions=7,formalGroups=27,formalPlannedTasks=2880,formalAttemptedTasks=0,
        referenceScreening=dict(plannedRequests=40,validRequests=40,compatiblePairs=20,widthLe50msPairs=0,minimumPairWidthMillis=107.249267578125,taskLevelP=False),
        successfulPublicPilot=dict(tasks=1,dispatchLateMillis=7.9153,downLateMillis=21.928,clickLateMillis=40.6767),
        framePilots=dict(secondsEach=120,draws=[3160,3590],uniqueAssociations=[3157,3584],parserErrors=[792,738],formalQuality=False),
        historical=quality['history'],tests=dict(core=61,app=6,independentParser=3,profilingPython=22,reportPython=5,failed=0),
        buildsAndLintPassed=True,completeUIRegressionRerun=False,gitDiffCheck='passed',
        restoration=dict(passed=restoration['passed'],originalApkSha256=restoration['originalApkSha256'],preferencesExact=True,
                         serviceBound=True,activeTraceSessions=0,testTargetRemoved=True),
        outputs=['PERFORMANCE_ANALYSIS_RESULTS_V3.md','PERFORMANCE_ANALYSIS_METHODS.md'],
        conclusion='P failed; executed evidence analyzed and archived. Full V3 remains incomplete; no worst-case or physical-limit claim.')
    write(Path('docs/acceptance/2026-10-28-performance-v3.json'),public)
    print(json.dumps(dict(files=len(files),bytes=sum(f['bytes'] for f in files),indexSha256=index_hash,formalTasks=0,measuredAdmission='not_passed')))


if __name__=='__main__':
    main()
