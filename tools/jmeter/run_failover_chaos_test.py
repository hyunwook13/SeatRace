#!/usr/bin/env python3
"""
Failover / zero-downtime chaos test for an Nginx -> Spring Boot pair.
Enhanced with Admin Port 50000 Traffic Draining Mechanism.
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import dataclasses
import itertools
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from typing import Optional


@dataclasses.dataclass
class Counters:
    total: int = 0
    success: int = 0
    failure: int = 0
    exceptions: int = 0
    by_status: Counter = dataclasses.field(default_factory=Counter)
    by_phase: Counter = dataclasses.field(default_factory=Counter)
    latency_samples_ms: list[float] = dataclasses.field(default_factory=list)


class Stats:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._data = Counters()

    def record(
        self,
        status: Optional[int],
        elapsed_ms: float,
        error_kind: Optional[str] = None,
        phase: Optional[str] = None,
    ) -> None:
        with self._lock:
            self._data.total += 1
            if status is not None:
                self._data.by_status[status] += 1
                if 200 <= status < 400:
                    self._data.success += 1
                else:
                    self._data.failure += 1
            else:
                self._data.failure += 1
                self._data.exceptions += 1

            self._data.by_status["_last_elapsed_ms"] = round(elapsed_ms, 2)
            self._data.latency_samples_ms.append(elapsed_ms)
            if error_kind:
                self._data.by_status[f"_last_error_{error_kind}"] += 1
            if phase:
                self._data.by_phase[phase] += 1

    def snapshot(self) -> Counters:
        with self._lock:
            return dataclasses.replace(
                self._data,
                by_status=Counter(self._data.by_status),
                by_phase=Counter(self._data.by_phase),
                latency_samples_ms=list(self._data.latency_samples_ms),
            )


class PhaseTracker:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._phase = "init"

    def set(self, phase: str) -> None:
        with self._lock:
            self._phase = phase

    def get(self) -> str:
        with self._lock:
            return self._phase


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Continuous traffic + container stop/start failover test."
    )
    parser.add_argument(
        "--target-url",
        default=os.environ.get("TARGET_URL", "http://localhost:8080/api/events/1/seats"),
        help="Target API URL. Default: %(default)s",
    )
    parser.add_argument(
        "--login-url",
        default=os.environ.get("LOGIN_URL", "http://localhost:8080/login"),
        help="Login endpoint URL. Default: %(default)s",
    )
    parser.add_argument(
        "--queue-url",
        default=os.environ.get("QUEUE_URL", "http://localhost:8080/api/events/1/queue/enter"),
        help="Queue entry URL. Default: %(default)s",
    )
    parser.add_argument(
        "--event-id",
        type=int,
        default=int(os.environ.get("EVENT_ID", "1")),
        help="Event ID used for queue and seat lookup. Default: %(default)s",
    )
    parser.add_argument(
        "--service1",
        default=os.environ.get("SERVICE1", "spring1"),
        help="First Spring container/service name. Default: %(default)s",
    )
    parser.add_argument(
        "--service2",
        default=os.environ.get("SERVICE2", "spring2"),
        help="Second Spring container/service name. Default: %(default)s",
    )
    parser.add_argument(
        "--rps",
        type=int,
        default=int(os.environ.get("RPS", "80")),
        help="Target requests per second. Default: %(default)s",
    )
    parser.add_argument(
        "--stop1-after",
        type=int,
        default=int(os.environ.get("STOP1_AFTER", "5")),
        help="Seconds to wait before stopping service1. Default: %(default)s",
    )
    parser.add_argument(
        "--wait-after-stop1",
        type=int,
        default=int(os.environ.get("WAIT_AFTER_STOP1", "15")),
        help="Seconds to keep traffic running after stopping service1. Default: %(default)s",
    )
    parser.add_argument(
        "--wait-after-start1",
        type=int,
        default=int(os.environ.get("WAIT_AFTER_START1", "20")),
        help="Seconds to wait after starting service1 again. Default: %(default)s",
    )
    parser.add_argument(
        "--wait-after-stop2",
        type=int,
        default=int(os.environ.get("WAIT_AFTER_STOP2", "15")),
        help="Seconds to keep traffic running after stopping service2. Default: %(default)s",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=float(os.environ.get("HTTP_TIMEOUT", "2.5")),
        help="Per-request HTTP timeout in seconds. Default: %(default)s",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=int(os.environ.get("MAX_WORKERS", "128")),
        help="Thread pool size for concurrent requests. Default: %(default)s",
    )
    parser.add_argument(
        "--username",
        default=os.environ.get("USERNAME", "user"),
        help="Login username. Default: %(default)s",
    )
    parser.add_argument(
        "--password",
        default=os.environ.get("PASSWORD", "1234"),
        help="Login password. Default: %(default)s",
    )
    parser.add_argument(
        "--skip-start-service2",
        action="store_true",
        help="Do not restart service2 at the end of the test.",
    )
    parser.add_argument(
        "--no-chaos",
        action="store_true",
        help="Only send traffic; do not stop or start containers.",
    )
    parser.add_argument(
        "--inspect-services",
        action="store_true",
        help="Print docker inspect state for both services around stop/start transitions.",
    )
    return parser


def run_cmd(cmd: list[str]) -> None:
    print(f"[cmd] {' '.join(cmd)}", flush=True)
    subprocess.run(cmd, check=True)


def inspect_service(service: str) -> str:
    try:
        out = subprocess.check_output(
            [
                "docker",
                "inspect",
                "-f",
                "{{.Name}} running={{.State.Running}} status={{.State.Status}} started={{.State.StartedAt}} finished={{.State.FinishedAt}}",
                service,
            ],
            text=True,
        ).strip()
        return out
    except subprocess.CalledProcessError as exc:
        return f"{service} inspect_failed rc={exc.returncode}"


def print_service_states(services: list[str], label: str, start_perf: float) -> None:
    print(f"[inspect {fmt_rel(start_perf)}] {label}")
    for service in services:
        print(f"  {inspect_service(service)}")


def rel_seconds(start_perf: float) -> float:
    return time.perf_counter() - start_perf


def fmt_rel(start_perf: float) -> str:
    return f"+{rel_seconds(start_perf):.3f}s"


def login_once(login_url: str, username: str, password: str, timeout: float) -> str:
    start = time.perf_counter()
    payload = urllib.parse.urlencode({"username": username, "password": password}).encode("utf-8")
    req = urllib.request.Request(
        login_url,
        data=payload,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        if resp.getcode() != 200:
            raise RuntimeError(f"login failed: status={resp.getcode()}, body={body[:200]}")
        try:
            import json
            parsed = json.loads(body)
        except Exception as exc:
            raise RuntimeError(f"login response is not valid JSON: {body[:200]}") from exc

        token = parsed.get("accessToken") or parsed.get("token")
        if not token:
            raise RuntimeError(f"login did not return accessToken/token: {body[:200]}")
        print(f"[auth] login ok in {elapsed_ms:.0f}ms")
        return token


def queue_enter_once(queue_url: str, access_token: str, timeout: float) -> str:
    start = time.perf_counter()
    req = urllib.request.Request(
        queue_url,
        data=b"",
        method="POST",
        headers={"Authorization": f"Bearer {access_token}"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        if resp.getcode() != 200:
            raise RuntimeError(f"queue enter failed: status={resp.getcode()}, body={body[:200]}")
        try:
            import json
            parsed = json.loads(body)
        except Exception as exc:
            raise RuntimeError(f"queue enter response is not valid JSON: {body[:200]}") from exc

        admitted = bool(parsed.get("admitted"))
        token = parsed.get("admissionToken")
        if not admitted or not token:
            raise RuntimeError(f"queue enter did not return admissionToken yet: {body[:200]}")
        print(f"[auth] queue admission ok in {elapsed_ms:.0f}ms")
        return token


def obtain_queue_token(queue_url: str, access_token: str, timeout: float, deadline_s: int = 60) -> str:
    deadline = time.time() + deadline_s
    last_error: Optional[Exception] = None

    while time.time() < deadline:
        try:
            return queue_enter_once(queue_url, access_token, timeout)
        except Exception as exc:
            last_error = exc
            time.sleep(0.5)

    raise RuntimeError(f"failed to obtain queue token within {deadline_s}s") from last_error


def request_once(url: str, access_token: str, queue_token: str, timeout: float) -> tuple[Optional[int], float, Optional[str]]:
    start = time.perf_counter()
    try:
        req = urllib.request.Request(
            url,
            method="GET",
            headers={
                "Authorization": f"Bearer {access_token}",
                "X-Queue-Token": queue_token,
            },
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = resp.getcode()
            resp.read(64)
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            return status, elapsed_ms, None
    except urllib.error.HTTPError as exc:
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return exc.code, elapsed_ms, f"http_{exc.code}"
    except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError) as exc:
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return None, elapsed_ms, type(exc).__name__.lower()
    except BaseException as exc:
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return None, elapsed_ms, type(exc).__name__.lower()


def request_once_with_phase(
    url: str,
    access_token: str,
    queue_token: str,
    timeout: float,
    phase: str,
    request_id: int,
    submitted_at_perf: float,
) -> tuple[Optional[int], float, Optional[str], str, int, float]:
    status, elapsed_ms, error_kind = request_once(url, access_token, queue_token, timeout)
    return status, elapsed_ms, error_kind, phase, request_id, submitted_at_perf


def wait_for_ready(
    url: str,
    access_token: str,
    queue_token: str,
    timeout: float,
    deadline_s: int = 60,
    consecutive_successes: int = 3,
    start_perf: Optional[float] = None,
) -> None:
    deadline = time.time() + deadline_s
    streak = 0
    last_status: Optional[int] = None
    last_error: Optional[str] = None

    while time.time() < deadline:
        status, elapsed_ms, error_kind = request_once(url, access_token, queue_token, timeout)
        last_status = status
        last_error = error_kind

        if status == 200:
            streak += 1
            prefix = f"[ready {fmt_rel(start_perf)}]" if start_perf else "[ready]"
            print(f"{prefix} {url} -> 200 ({streak}/{consecutive_successes}) in {elapsed_ms:.0f}ms")
            if streak >= consecutive_successes:
                return
        else:
            streak = 0
            prefix = f"[ready {fmt_rel(start_perf)}]" if start_perf else "[ready]"
            print(f"{prefix} waiting for 200: status={status}, error={error_kind}")

        time.sleep(1)

    raise RuntimeError(
        f"service did not become ready within {deadline_s}s "
        f"(last_status={last_status}, last_error={last_error})"
    )


def wait_for_stable_service(
    url: str,
    access_token: str,
    queue_token: str,
    timeout: float,
    stable_seconds: int = 8,
    poll_interval: float = 1.0,
    start_perf: Optional[float] = None,
) -> None:
    deadline = time.time() + stable_seconds
    last_status: Optional[int] = None
    last_error: Optional[str] = None

    while time.time() < deadline:
        status, elapsed_ms, error_kind = request_once(url, access_token, queue_token, timeout)
        last_status = status
        last_error = error_kind

        if status != 200:
            raise RuntimeError(
                f"service is not yet stable before next stop "
                f"(status={status}, error={error_kind}, elapsed_ms={elapsed_ms:.0f})"
            )

        time.sleep(poll_interval)

    prefix = f"[ready {fmt_rel(start_perf)}]" if start_perf else "[ready]"
    print(f"{prefix} service stayed stable for {stable_seconds}s before next stop")


def traffic_pump(
    url: str,
    access_token: str,
    queue_token: str,
    rps: int,
    timeout: float,
    max_workers: int,
    phase_tracker: PhaseTracker,
    stats: Stats,
    stop_event: threading.Event,
    start_perf: float,
) -> None:
    if rps <= 0:
        raise ValueError("rps must be greater than 0")

    interval = 1.0 / float(rps)
    next_tick = time.perf_counter()
    request_seq = itertools.count(1)

    with cf.ThreadPoolExecutor(max_workers=max(4, max_workers)) as pool:
        def submit_one() -> None:
            phase = phase_tracker.get()
            request_id = next(request_seq)
            submitted_at_perf = time.perf_counter()
            fut = pool.submit(
                request_once_with_phase,
                url,
                access_token,
                queue_token,
                timeout,
                phase,
                request_id,
                submitted_at_perf,
            )

            def _done(done: cf.Future) -> None:
                try:
                    status, elapsed_ms, error_kind, phase_name, request_id, submitted_perf = done.result()
                    stats.record(status, elapsed_ms, error_kind, phase_name)
                    if status is None or status >= 400:
                        queued_at = submitted_perf - start_perf
                        finished_at = time.perf_counter() - start_perf
                        print(
                            f"[fail {fmt_rel(start_perf)}] req={request_id} phase={phase_name} "
                            f"queued_at={queued_at:.3f}s status={status} "
                            f"error={error_kind} elapsed_ms={elapsed_ms:.0f} "
                            f"done_at={finished_at:.3f}s",
                            flush=True,
                        )
                except Exception as exc:
                    phase_name = phase_tracker.get()
                    stats.record(None, 0.0, type(exc).__name__.lower(), phase_name)
                    print(f"[fail {fmt_rel(start_perf)}] phase={phase_name} exception={type(exc).__name__}: {exc}", flush=True)

            fut.add_done_callback(_done)

        while not stop_event.is_set():
            now = time.perf_counter()
            if now < next_tick:
                time.sleep(min(next_tick - now, 0.01))
                continue
            submit_one()
            next_tick += interval


def print_banner(title: str) -> None:
    print()
    print("=" * 76)
    print(title)
    print("=" * 76)


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    if args.rps < 1:
        print("ERROR: --rps must be >= 1", file=sys.stderr)
        return 2

    if not shutil_which("docker"):
        print("ERROR: docker command not found in PATH", file=sys.stderr)
        return 2

    access_token = login_once(args.login_url, args.username, args.password, args.timeout)
    queue_token = obtain_queue_token(args.queue_url, access_token, args.timeout)

    phase_tracker = PhaseTracker()
    stats = Stats()
    stop_event = threading.Event()
    start_perf = time.perf_counter()
    pump_thread = threading.Thread(
        target=traffic_pump,
        args=(
            args.target_url,
            access_token,
            queue_token,
            args.rps,
            args.timeout,
            args.max_workers,
            phase_tracker,
            stats,
            stop_event,
            start_perf,
        ),
        daemon=True,
    )

    print_banner("Failover chaos test start")
    print(f"target_url          : {args.target_url}")
    print(f"login_url           : {args.login_url}")
    print(f"queue_url           : {args.queue_url}")
    print(f"service1 / service2 : {args.service1} / {args.service2}")
    print(f"event_id            : {args.event_id}")
    print(f"rps                 : {args.rps}")
    print(f"timeouts            : {args.timeout}s per request")
    print(f"timeline Suspend    : drain1 -> stop1@{args.stop1_after}s -> wait {args.wait_after_stop1}s -> "
          f"start1 -> wait {args.wait_after_start1}s -> drain2 -> stop2 -> wait {args.wait_after_stop2}s")
    print()

    phase_tracker.set("pre-stop1")
    pump_thread.start()

    try:
        services = [args.service1, args.service2]
        if args.no_chaos:
            print_banner("Traffic only mode")
            print(
                f"[wait {fmt_rel(start_perf)}] running traffic for "
                f"{args.stop1_after + args.wait_after_stop1 + args.wait_after_start1 + args.wait_after_stop2}s"
            )
            if args.inspect_services:
                print_service_states(services, "before traffic-only window", start_perf)
            phase_tracker.set("traffic-only")
            time.sleep(args.stop1_after + args.wait_after_stop1 + args.wait_after_start1 + args.wait_after_stop2)
        else:
            print(f"[phase {fmt_rel(start_perf)}] entering pre-stop1 warmup for {args.stop1_after}s")
            if args.inspect_services:
                print_service_states(services, "before stop1", start_perf)
            time.sleep(args.stop1_after)

            # ============================================================================
            # 💡 [1번 서버] 선 트래픽 드레인(Drain) 후 순차 종료
            # ============================================================================
            phase_tracker.set("stop1")
            print_banner(f"Draining and Stopping {args.service1}")

            try:
                # 격리된 내부 포트 50000번으로 우아한 종료(Readiness 끄기) API를 먼저 요청합니다.
                run_cmd(["docker", "compose", "exec", "-T", "nginx", "curl", "-X", "POST", f"http://{args.service1}:50000/actuator/shutdown"])
            except Exception as e:
                print(f"[warn] Shutdown endpoint triggered for {args.service1}: {e}")

            print(f"[drain {fmt_rel(start_perf)}] Waiting 5s for {args.service1} connections to drain completely...")
            time.sleep(5) # Nginx가 확실하게 1번을 아웃시키고 남은 요청을 흘려보낼 시간을 줍니다.

            run_cmd(["docker", "stop", args.service1])
            if args.inspect_services:
                print_service_states(services, "right after stop1", start_perf)

            phase_tracker.set("after-stop1")
            print(f"[wait {fmt_rel(start_perf)}] monitoring traffic for {args.wait_after_stop1}s after {args.service1} stop")
            time.sleep(args.wait_after_stop1)

            # ============================================================================
            # [1번 서버] 부활 및 Nginx 신뢰성 타이머 대기
            # ============================================================================
            phase_tracker.set("start1")
            print_banner(f"Starting {args.service1}")
            run_cmd(["docker", "start", args.service1])
            if args.inspect_services:
                print_service_states(services, "right after start1", start_perf)

            phase_tracker.set("wait-start1")
            print(f"[wait {fmt_rel(start_perf)}] allowing {args.service1} to boot for up to {args.wait_after_start1}s")
            wait_for_ready(
                args.target_url,
                access_token,
                queue_token,
                args.timeout,
                deadline_s=args.wait_after_start1,
                start_perf=start_perf,
            )

            print(f"[wait {fmt_rel(start_perf)}] verifying the surviving service stays stable before stopping the other replica")
            wait_for_stable_service(
                args.target_url,
                access_token,
                queue_token,
                args.timeout,
                stable_seconds=8, # 5초의 fail_timeout 격리가 완벽히 해제되도록 보장
                start_perf=start_perf,
            )

            # ============================================================================
            # 💡 [2번 서버] 선 트래픽 드레인(Drain) 후 순차 종료
            # ============================================================================
            phase_tracker.set("stop2")
            print_banner(f"Draining and Stopping {args.service2}")

            try:
                # 2번 서버도 끄기 전 내부망 50000 포트의 Actuator 셧다운으로 안전하게 트래픽 드레인을 가동합니다.
                run_cmd(["docker", "compose", "exec", "-T", "nginx", "curl", "-X", "POST", f"http://{args.service2}:50000/actuator/shutdown"])
            except Exception as e:
                print(f"[warn] Shutdown endpoint triggered for {args.service2}: {e}")

            print(f"[drain {fmt_rel(start_perf)}] Waiting 5s for {args.service2} connections to drain completely...")
            time.sleep(5)

            run_cmd(["docker", "stop", args.service2])
            if args.inspect_services:
                print_service_states(services, "right after stop2", start_perf)

            phase_tracker.set("after-stop2")
            print(f"[wait {fmt_rel(start_perf)}] monitoring traffic for {args.wait_after_stop2}s after {args.service2} stop")
            time.sleep(args.wait_after_stop2)

            if not args.skip_start_service2:
                phase_tracker.set("start2")
                print_banner(f"Restoring {args.service2}")
                run_cmd(["docker", "start", args.service2])
                if args.inspect_services:
                    print_service_states(services, "right after start2", start_perf)

    except KeyboardInterrupt:
        print("\n[abort] interrupted by user", file=sys.stderr)
        stop_event.set()
        pump_thread.join(timeout=5)
        return 130
    except subprocess.CalledProcessError as exc:
        print(f"\n[error] command failed: {exc}", file=sys.stderr)
        stop_event.set()
        pump_thread.join(timeout=5)
        return exc.returncode or 1
    finally:
        stop_event.set()
        pump_thread.join(timeout=10)

    elapsed = time.perf_counter() - start_perf
    snap = stats.snapshot()
    error_rate = (snap.failure / snap.total * 100.0) if snap.total else 0.0

    print_banner("Final report")
    print(f"elapsed seconds   : {elapsed:.2f}")
    print(f"total requests    : {snap.total}")
    print(f"success requests  : {snap.success}")
    print(f"failure requests  : {snap.failure}")
    print(f"exceptions        : {snap.exceptions}")
    print(f"error rate        : {error_rate:.2f}%")

    if snap.latency_samples_ms:
        samples = sorted(snap.latency_samples_ms)
        count = len(samples)
        avg = sum(samples) / count
        p50 = samples[int(count * 0.50)]
        p95 = samples[min(count - 1, int(count * 0.95))]
        p99 = samples[min(count - 1, int(count * 0.99))]
        mx = samples[-1]
        print()
        print("latency summary (ms):")
        print(f"  avg: {avg:.2f}")
        print(f"  p50: {p50:.2f}")
        print(f"  p95: {p95:.2f}")
        print(f"  p99: {p99:.2f}")
        print(f"  max: {mx:.2f}")

    if snap.by_status:
        print()
        print("status breakdown:")
        for key in sorted(k for k in snap.by_status.keys() if isinstance(k, int)):
            print(f"  {key}: {snap.by_status[key]}")
        for key in sorted(k for k in snap.by_status.keys() if isinstance(k, str) and k.startswith("_last_")):
            print(f"  {key}: {snap.by_status[key]}")

    if snap.by_phase:
        print()
        print("phase breakdown:")
        for key in sorted(snap.by_phase.keys()):
            print(f"  {key}: {snap.by_phase[key]}")

    return 0 if snap.failure == 0 else 1


if __name__ == "__main__":
    import shutil
    def shutil_which(cmd: str) -> Optional[str]:
        return shutil.which(cmd)

    raise SystemExit(main())