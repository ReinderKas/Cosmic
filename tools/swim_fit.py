"""Decode swim packet logs and fit drag/gravity/thrust constants.

Model: dv/dt = g - thrust - k*v   (vy positive=down, thrust>0 = upward)
- burst.log:        thrust = 0           (no UP held)
- burst-upheld.log: thrust = UP_thrust   (UP arrow held)
- downheld.log:     thrust = -DOWN_thrust (DOWN arrow held, NEGATIVE thrust adds gravity)

Run: py D:\\GameServers\\Maplestory\\Cosmic\\tools\\swim_fit.py
"""
import os, re, sys, struct
import numpy as np
from scipy.optimize import minimize, differential_evolution

LOG_DIR = r"D:\GameServers\Maplestory\Cosmic\logs"

NORMAL_TYPES = {0, 5, 17}
JUMP_TYPES = {1, 2, 6, 12, 13, 16, 18, 19, 20}


def parse_line(hex_str):
    b = bytes(int(x, 16) for x in hex_str.split())
    p = 0
    def r16():
        nonlocal p
        v = struct.unpack_from("<h", b, p)[0]; p += 2; return v
    def r8():
        nonlocal p
        v = b[p]; p += 1; return v
    p += 4  # skip 4-byte client tick
    ox, oy = r16(), r16()
    cnt = r8()
    segs = []
    for _ in range(cnt):
        t = r8()
        if t in NORMAL_TYPES:
            x, y = r16(), r16()
            vx, vy = r16(), r16()
            fh = r16(); st = r8(); dur = r16()
            segs.append({"type": "N", "t": t, "y": y, "vy": vy, "dur": dur, "st": st})
        elif t in JUMP_TYPES:
            vx, vy = r16(), r16(); st = r8(); dur = r16()
            segs.append({"type": "J", "t": t, "vy": vy, "dur": dur, "st": st})
        else:
            return None
    return ox, oy, cnt, segs


def load_log(path):
    """Returns list of (label, segs_with_origin)."""
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            m = re.search(r"41\(0x29\)-\d{2}\s+([0-9A-F\s]+)$", line.strip())
            if not m:
                continue
            res = parse_line(m.group(1))
            if res:
                rows.append(res)
    return rows


def extract_arcs(rows, label):
    """Yield (t_arc, vy_arc, y_arc) where each arc starts at a JUMP impulse
    (or first NORMAL segment of file) and ends at last NORMAL of that packet."""
    arcs = []
    for ox, oy, cnt, segs in rows:
        cum_dur = 0
        last_arc_start_t = 0
        last_arc_start_vy = None
        last_arc_start_y = oy
        # Walk segments, building the post-burst arc within each row
        cur_arc = []
        prev_y = oy
        for seg in segs:
            if seg["type"] == "J":
                # Burst: reset arc with new vy0
                cur_arc = [(0.0, seg["vy"], prev_y)]
                cum_dur = 0
                last_arc_start_t = 0
            elif seg["type"] == "N":
                cum_dur += seg["dur"]
                cur_arc.append((cum_dur / 1000.0, seg["vy"], seg["y"]))
                prev_y = seg["y"]
        if len(cur_arc) >= 2 and cur_arc[0][1] is not None:
            arcs.append({"label": label, "arc": cur_arc})
    return arcs


def simulate(vy0, g, k1, k2, thrust, t_end, dt=0.002):
    """Forward Euler integration with linear+quadratic drag:
       dv/dt = (g - thrust) - k1*v - k2*v*|v|
    The quadratic term lets high-speed regime damp fast (large effective
    friction during burst) while low-speed regime has small drag (matching
    small terminal velocities observed when no key is held)."""
    vy = float(vy0); y = 0.0
    steps = int(round(t_end / dt))
    g_eff = g - thrust
    for _ in range(steps):
        vy += (g_eff - k1 * vy - k2 * vy * abs(vy)) * dt
        y += vy * dt
    return vy, y


def residuals(params, dataset):
    g, k1, k2, UP, DN = params
    s = 0.0
    for label, vy0, dur, vy_t, dy_t in dataset:
        if label == "burst":
            thrust = 0.0
        elif label == "upheld":
            thrust = UP
        elif label == "downheld":
            thrust = -DN
        elif label == "nokey":
            thrust = 0.0
        else:
            continue
        vy_sim, y_sim = simulate(vy0, g, k1, k2, thrust, dur)
        s += (vy_sim - vy_t) ** 2
        if dy_t is not None:
            s += (y_sim - dy_t) ** 2 * 0.1
    return s


def main():
    burst_rows = load_log(os.path.join(LOG_DIR, "monitored-packets-swim-burst.log"))
    upheld_rows = load_log(os.path.join(LOG_DIR, "monitored-packets-swim-burst-upheld.log"))
    downheld_rows = load_log(os.path.join(LOG_DIR, "monitored-packets-swim-downheld.log"))
    upheldonly_rows = load_log(os.path.join(LOG_DIR, "monitored-packets-swim-upheld.log"))

    # Build dataset: each row is (label, vy0, dur_seconds, vy_endpoint, dy)
    # Carefully extract burst-arc segments from packets.
    dataset = []

    def extract_burst_arcs(rows, label):
        """Find each JUMP segment, then track subsequent NORMAL segments
        within the SAME packet as the post-burst arc.  Each (vy0, dur, vy_end, dy)
        becomes a fitting target."""
        out = []
        for ox, oy, cnt, segs in rows:
            arc_active = False
            arc_vy0 = None
            arc_t = 0.0
            arc_y_start = None
            for seg in segs:
                if seg["type"] == "J":
                    arc_active = True
                    arc_vy0 = seg["vy"]
                    arc_t = 0.0
                    arc_y_start = None  # use next NORMAL's seg start as ref
                elif seg["type"] == "N":
                    if arc_active:
                        if arc_y_start is None:
                            # First NORMAL after burst — record arc start y
                            # (assume burst happened just before this NORMAL,
                            #  so arc covers this NORMAL's full dur)
                            arc_t += seg["dur"] / 1000.0
                            out.append({
                                "label": label,
                                "vy0": arc_vy0,
                                "dur": arc_t,
                                "vy_end": seg["vy"],
                                "dy": seg["y"] - oy,  # delta from packet origin
                            })
        return out

    def extract_steady_terminals(rows, label):
        """For DOWN/no-key terminals, look at packets with single NORMAL
        segment and constant vy."""
        out = []
        for ox, oy, cnt, segs in rows:
            if cnt == 1 and segs[0]["type"] == "N":
                seg = segs[0]
                # If start vy ≈ end vy ≈ steady, treat as terminal observation
                out.append({
                    "label": label,
                    "vy0": seg["vy"],   # approximate same-as-end
                    "dur": seg["dur"] / 1000.0,
                    "vy_end": seg["vy"],
                    "dy": seg["y"] - oy,
                })
        return out

    burst_arcs = extract_burst_arcs(burst_rows, "burst")
    upheld_arcs = extract_burst_arcs(upheld_rows, "upheld")
    downheld_terms = extract_steady_terminals(downheld_rows, "downheld")
    upheldonly_arcs = extract_burst_arcs(upheldonly_rows, "upheld")
    print(f"burst arcs: {len(burst_arcs)}")
    print(f"upheld arcs: {len(upheld_arcs) + len(upheldonly_arcs)}")
    print(f"downheld terminals: {len(downheld_terms)}")

    print("\n=== Sample burst arcs ===")
    for a in burst_arcs[:6]:
        print(f"  vy0={a['vy0']} -> vy={a['vy_end']} in {a['dur']*1000:.0f}ms, dy={a['dy']}")
    print("\n=== Sample upheld arcs ===")
    for a in upheld_arcs[:6]:
        print(f"  vy0={a['vy0']} -> vy={a['vy_end']} in {a['dur']*1000:.0f}ms, dy={a['dy']}")
    print("\n=== Downheld terminals (vy stays constant) ===")
    seen = set()
    for a in downheld_terms[:10]:
        if a["vy_end"] in seen: continue
        seen.add(a["vy_end"])
        print(f"  steady vy={a['vy_end']}")

    # Build dataset for fitter
    rows_dataset = []
    for a in burst_arcs:
        rows_dataset.append(("burst", a["vy0"], a["dur"], a["vy_end"], None))
    for a in upheld_arcs:
        rows_dataset.append(("upheld", a["vy0"], a["dur"], a["vy_end"], None))
    for a in upheldonly_arcs:
        rows_dataset.append(("upheld", a["vy0"], a["dur"], a["vy_end"], None))

    # Steady-state terminal observations as zero-derivative constraints:
    # at terminal, dv/dt = 0 = g_eff - k1*v - k2*v*|v| → solved via simulation
    # over a long-enough duration starting near terminal.
    # 140 = no-key terminal; 210 = DOWN-held terminal.
    seen_terminals = set()
    for a in downheld_terms:
        v = a["vy_end"]
        if v in seen_terminals:
            continue
        seen_terminals.add(v)
        # Constraint: starting at v, after 1s, should still be at v (steady).
        if v == 140:
            rows_dataset.append(("nokey", v, 1.0, v, None))
        elif v == 210:
            rows_dataset.append(("downheld", v, 1.0, v, None))

    print(f"\nFitting against {len(rows_dataset)} data points...")
    bounds = [(100, 5000),     # g
              (0.0, 10.0),     # k1 (linear drag)
              (0.0, 0.05),     # k2 (quadratic drag)
              (0, 5000),       # UP_thrust
              (0, 5000)]       # DOWN_thrust
    res = differential_evolution(residuals, bounds, args=(rows_dataset,),
                                 seed=42, tol=1e-6, maxiter=300, popsize=15, polish=True,
                                 workers=1)
    g, k1, k2, UP, DN = res.x

    # Compute terminal velocities by simulating from far-from-equilibrium.
    def find_terminal(thrust):
        return simulate(0, g, k1, k2, thrust, 5.0)[0]

    print(f"\nBEST FIT (linear+quadratic drag):")
    print(f"  g            = {g:.1f}  px/s²")
    print(f"  k1 (linear)  = {k1:.4f} /s")
    print(f"  k2 (quad)    = {k2:.6f} 1/(px·s)")
    print(f"  UP_thrust    = {UP:.1f}  px/s²")
    print(f"  DOWN_thrust  = {DN:.1f}  px/s²")
    print(f"  no-key terminal sink   = {find_terminal(0):.1f} px/s   (target ~140)")
    print(f"  UP-held terminal sink  = {find_terminal(UP):.1f} px/s   (slow descent)")
    print(f"  DOWN-held terminal sink= {find_terminal(-DN):.1f} px/s  (target 210)")
    print(f"  residual SS= {res.fun:.1f}")

    print("\n=== Per-arc fit error (sample) ===")
    for label, vy0, dur, vy_t, _ in rows_dataset[:12]:
        thrust = 0 if label in ("burst", "nokey") else (UP if label == "upheld" else -DN)
        vy_sim, _ = simulate(vy0, g, k1, k2, thrust, dur)
        print(f"  {label:9s} vy0={vy0:>5} dur={dur*1000:>5.0f}ms: sim={vy_sim:>6.1f} obs={vy_t:>5} err={vy_sim-vy_t:+.1f}")


if __name__ == "__main__":
    main()
