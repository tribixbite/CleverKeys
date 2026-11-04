#!/usr/bin/env python3
"""Print the kit-17 fine-tune command, derived from the shipped run's own recorded args.

The shipped English encoder is run `v2kd-fresh-w1` (CleverKeys-ML ctc/UNSEALING_4.md §2.3;
ship artifact sha256 84718e6e…88e5). `train.py` stores `vars(args)` in every checkpoint, so
the exact recipe is read back from `ckpt/v2kd-fresh-w1/best.pt` instead of being retyped.
The flag names come from train.py's OWN parser (captured by intercepting `parse_args`), so a
renamed flag cannot be silently mis-spelled here.

Changes against the shipped recipe (short-word §6.3 + final-letter-drops §6.4), and nothing
else:
  --init-from   the shipped checkpoint (fine-tune, not from scratch)
  --kd-teacher  the shipped checkpoint, --kd-weight 1.0 (hold broad accuracy)
  --short-loss-weight 1.5, --t-out 32 (unchanged I/O contract)
  --train-npz   the contract-rewritten (_fix) pools + the targeted synth pool
  --word-balance-tau TAU, --short-final-boost 2.0, --synth-pools <synth>, --synth-frac 0.08
  --total-steps STEPS, --seed SEED, --run-name NAME

Usage (from CleverKeys-ML/ctc, after the patch is applied):
  python3 $KIT/finetune_cmd.py --workdir $WD --tau 0.5 --seed 1234 --steps 60000 \
      --run-name sw17-t05-s1234
"""
from __future__ import annotations

import argparse
import shlex
import sys
from pathlib import Path
from typing import Dict, List

SHIPPED_RUN = "v2kd-fresh-w1"
#: Keys train.py adds to args after parsing (provenance), never flags.
DERIVED = {"kd_teacher_sha256", "init_from_sha256"}


class _Captured(Exception):
    def __init__(self, parser: argparse.ArgumentParser) -> None:
        super().__init__("parser captured")
        self.parser = parser


def train_parser(ctc: Path) -> argparse.ArgumentParser:
    """train.py's real ArgumentParser, captured at its parse_args() call."""
    sys.path.insert(0, str(ctc))
    import train  # noqa: E402  CleverKeys-ML ctc/train.py (patched)
    orig = argparse.ArgumentParser.parse_args

    def grab(self: argparse.ArgumentParser, *a: object, **k: object) -> None:
        raise _Captured(self)
    argparse.ArgumentParser.parse_args = grab  # type: ignore[assignment]
    try:
        train.main()
    except _Captured as c:
        return c.parser
    finally:
        argparse.ArgumentParser.parse_args = orig  # type: ignore[assignment]
    raise SystemExit("train.main() returned without parsing args")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ctc", type=Path, default=Path("."))
    ap.add_argument("--workdir", type=Path, required=True)
    ap.add_argument("--tau", type=float, required=True)
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--steps", type=int, default=60_000)
    ap.add_argument("--run-name", required=True, dest="run_name")
    ap.add_argument("--synth", default="synth_en_shortword.npz")
    ap.add_argument("--pools", default="",
                    help="override the derived pool list (default: every REAL pool of the shipped "
                         "run renamed X.npz -> X_fix.npz, order and repeats kept; synth* pools kept "
                         "as they are and capped together with --synth)")
    a = ap.parse_args()
    import torch  # local: only needed to read the checkpoint
    ck_path = a.workdir / "ckpt" / SHIPPED_RUN / "best.pt"
    shipped: Dict[str, object] = torch.load(ck_path, map_location="cpu", weights_only=False)["args"]
    parser = train_parser(a.ctc.resolve())
    shipped_npz = str(shipped.get("train_npz", ""))
    print(f"# shipped run {SHIPPED_RUN} trained on --train-npz {shipped_npz}", file=sys.stderr)
    names = [n.strip() for n in shipped_npz.split(",") if n.strip()]
    synth = [n for n in dict.fromkeys(names) if n.startswith("synth")] + [a.synth]
    pools = a.pools or ",".join(n if n.startswith("synth") else n[: -len(".npz")] + "_fix.npz" for n in names)
    for n in set(pools.split(",")) | set(synth):
        if not (a.workdir / "cache" / n).exists():
            raise SystemExit(f"missing pool {a.workdir / 'cache' / n} (run steps 3-4 of the README first)")
    over: Dict[str, object] = {
        "init_from": str(ck_path), "kd_teacher": str(ck_path), "kd_weight": 1.0,
        "short_loss_weight": 1.5, "t_out": 32, "resume": "",
        "train_npz": f"{pools},{a.synth}", "word_balance_tau": a.tau, "short_final_boost": 2.0,
        "synth_pools": ",".join(synth), "synth_frac": 0.08, "total_steps": a.steps, "seed": a.seed,
        "run_name": a.run_name, "workdir": str(a.workdir),
    }
    args = {**shipped, **over}
    cmd: List[str] = ["python3", "train.py"]
    known = set()
    for act in parser._actions:  # noqa: SLF001  argparse exposes no public action list
        if not act.option_strings or act.dest == "help":
            continue
        known.add(act.dest)
        if act.dest not in args:
            continue
        v = args[act.dest]
        flag = max(act.option_strings, key=len)
        if isinstance(act, argparse._StoreTrueAction):  # noqa: SLF001
            if v:
                cmd.append(flag)
        elif isinstance(act, argparse._StoreFalseAction):  # noqa: SLF001
            if not v:
                cmd.append(flag)
        elif v is not None and v != act.default:
            cmd += [flag, str(v)]
    unknown = set(args) - known - DERIVED
    if unknown:
        raise SystemExit(f"checkpoint args with no train.py flag (recipe drift?): {sorted(unknown)}")
    print(" ".join(shlex.quote(c) for c in cmd))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
