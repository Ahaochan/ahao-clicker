"""Summarize measured receipt latency and long-wait clock observations; Python standard library only."""
import argparse
import json
import math
import statistics
from pathlib import Path


def distribution(values, limit=None):
    ordered = sorted(values)
    if not ordered:
        return None
    result = {"count": len(values), "min": ordered[0], "p50": statistics.median(ordered),
              "p95": ordered[math.ceil(len(ordered) * .95) - 1],
              "p99": ordered[math.ceil(len(ordered) * .99) - 1], "max": ordered[-1]}
    if limit is not None:
        over = sum(value > limit for value in values)
        result.update(limit=limit, overLimitCount=over, overLimitPercent=100 * over / len(values))
    return result


def summarize(directory):
    root = Path(directory)
    result = json.loads((root / "result.json").read_text(encoding="utf-8-sig"))
    if "rows" in result:
        rows = [row for row in result["rows"] if "error" not in row]
        output = {"passed": result["passed"], "observedDurationMillis": result.get("observedDurationMillis"),
                  "samples": len(result["rows"]), "validSamples": len(rows),
                  "failedOracleSamples": len(result["rows"]) - len(rows),
                  "distinctSuccessfulSyncs": len({row["lastSuccessElapsed"] for row in rows})}
        if "error" in result:
            output["error"] = result["error"]
        for key in ["displayErrorMillis", "referenceErrorMillis", "drawAgeMillis", "oracleRttMillis", "oracleUncertaintyMillis", "sourceRttMillis"]:
            output[key] = distribution([row[key] for row in rows])
        for key in ["referenceToDisplayLagMillis", "minimumAbsErrorMillis", "maximumAbsErrorMillis"]:
            if rows and key in rows[0]:
                output[key] = distribution([row[key] for row in rows])
        for key in ["errorLimitMillis", "estimatedOverLimitSamples", "confirmedOverLimitSamples", "inconclusiveSamples", "withinLimitConsideringOracleRtt", "strictLimitCertified", "estimatedLimitPassed"]:
            if key in result:
                output[key] = result[key]
        output["syncAgeMillis"] = distribution([row["elapsed"] - row["lastSuccessElapsed"] for row in rows])
        successes = sorted({row["lastSuccessElapsed"] for row in rows})
        output["observedSuccessGapMillis"] = distribution([b - a for a, b in zip(successes, successes[1:])])
        output["elapsedMinusUptimeMillis"] = distribution([row["elapsed"] - row["uptime"] for row in rows if "uptime" in row])
        output["targetFocusedSamples"] = sum(row.get("targetFocused", False) for row in rows)
        output["unexpectedTargetClicks"] = result.get("unexpectedTargetClicks")
        output["displayAbsoluteErrorMillis"] = distribution([abs(row["displayErrorMillis"]) for row in rows], limit=50)
        output["secondLevelErrors"] = sum(abs(row["displayErrorMillis"]) >= 1000 for row in rows)
        if len(rows) >= 4:
            quarter = max(1, len(rows) // 4)
            output["firstQuarterMedianErrorMillis"] = statistics.median(row["displayErrorMillis"] for row in rows[:quarter])
            output["lastQuarterMedianErrorMillis"] = statistics.median(row["displayErrorMillis"] for row in rows[-quarter:])
            minutes = [(row["elapsed"] - rows[0]["elapsed"]) / 60000 for row in rows]
            output["errorTrendMillisPerMinute"] = statistics.linear_regression(minutes, [row["displayErrorMillis"] for row in rows]).slope
        if rows:
            output["drawCountIncrease"] = rows[-1]["draws"] - rows[0]["draws"]
            output["observationSpanMillis"] = rows[-1]["elapsed"] - rows[0]["elapsed"]
    else:
        output = {"passed": result["passed"], "appInstrumentationAttached": result.get("appInstrumentationAttached"),
                  "received": len(result["samples"]), "latencyMillis": {}}
        failure = result.get("failureSnapshot", {})
        failed_index = result.get("failureIndex", failure.get("index"))
        attempted = len(result["samples"]) if result["passed"] else (
            max(len(result["samples"]), failed_index + 1) if failed_index is not None else None)
        requested = result.get("requestedSamples")
        events = result.get("failureEvents", failure.get("targetEvents"))
        output.update(requestedTasks=requested, attemptedTasks=attempted,
                      notAttemptedTasks=max(0, requested - attempted) if requested is not None and attempted is not None else None,
                      failureObservedOnClicks=len(events) if events is not None else None)
        if not result["passed"]:
            output["error"] = result.get("error")
        for key in ["dispatchedElapsed", "downReceivedElapsed", "clickReceivedElapsed"]:
            values = [row[key] - row["plannedElapsed"] for row in result["samples"]]
            output["latencyMillis"][key] = distribution(values, limit=50)
            if values:
                output["latencyMillis"][key]["early"] = sum(value < 0 for value in values)
        if result["samples"] and all("upReceivedElapsed" in row for row in result["samples"]):
            output["inputDurationMillis"] = distribution([row["upEventUptime"] - row["downEventUptime"] for row in result["samples"]])
            output["downToUpReceiptMillis"] = distribution([row["upReceivedElapsed"] - row["downReceivedElapsed"] for row in result["samples"]])
            output["upToClickReceiptMillis"] = distribution([row["clickReceivedElapsed"] - row["upReceivedElapsed"] for row in result["samples"]])
        observations = result.get("soak", [])
        if observations:
            output["longWait"] = {
                "observations": len(observations),
                "observationSpanMillis": observations[-1]["elapsed"] - observations[0]["elapsed"],
                "freshObservations": sum(row["fresh"] for row in observations),
                "referenceAgeMillis": distribution([row["elapsed"] - row["lastSuccessElapsed"] for row in observations]),
                "taskMinusLatestSourceMillis": distribution([row["taskEpoch"] - row["sourceEpoch"] for row in observations if "taskEpoch" in row]),
                "deadlineCorrectionsMillis": [row["plannedElapsed"] - observations[0]["plannedElapsed"] for row in observations],
                "distinctSuccessfulSyncs": len({row["lastSuccessElapsed"] for row in observations}),
            }
    samples_file = root / "resource-samples.jsonl"
    if samples_file.exists():
        resources = [json.loads(line) for line in samples_file.read_text(encoding="utf-8").splitlines() if line]
        cpu = []
        for before, after in zip(resources, resources[1:]):
            seconds = after["elapsedSeconds"] - before["elapsedSeconds"]
            if before["pid"] == after["pid"] and before["startTicks"] == after["startTicks"] and seconds > 0:
                cpu.append(100 * (after["cpuTicks"] - before["cpuTicks"]) / after["clockTicks"] / seconds)
        output["resources"] = {"samples": len(resources), "processIdentities": len({(row["pid"],row["startTicks"]) for row in resources}),
                               "pssKiB": distribution([row["pssKiB"] for row in resources]),
                               "firstPssKiB": resources[0]["pssKiB"] if resources else None,
                               "lastPssKiB": resources[-1]["pssKiB"] if resources else None,
                               "singleCoreCpuPercent": distribution(cpu)}
        if len(resources) > 4:
            warm = resources[4:]
            output["resources"]["afterWarmupPssKiB"] = distribution([row["pssKiB"] for row in warm])
            output["resources"]["afterWarmupFirstPssKiB"] = warm[0]["pssKiB"]
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = {Path(directory).name: summarize(directory) for directory in args.directories}
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text)
