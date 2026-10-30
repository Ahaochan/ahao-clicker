"""Transport completion is independent of an application's measurement result."""


def transport_errors(report, exit_key, pull_keys):
    if not isinstance(report,dict):return ['invalid_report']
    errors=[]
    if 'stoppedReason' not in report or report['stoppedReason'] is not None:errors.append('stoppedReason')
    for key in [exit_key]+list(pull_keys):
        if type(report.get(key)) is not int or report[key]!=0:errors.append(key)
    return errors
