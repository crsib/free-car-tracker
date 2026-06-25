# Li Auto (Lixiang) — Car Scanner profile request set (captured)

Source: honeypot capture `tools/li-profile-requests.csv`, 2026-06-25.
Car Scanner connected with its **Lixiang profile** hand-selected, against the
`carscanner_capture.py` honeypot (replaying the real car's `init-1782405839288.csv`
identification — so `221201`/`22F190` answered `NO DATA`, exactly like the real car).
The profile still enumerated fully, so these are the **exact requests** Car Scanner's
Lixiang profile emits. Trailing `1` in the raw log is the ELM "responses expected" hint;
canonical command = first 6 hex chars for DIDs / 4 for PIDs.

## Manufacturer telemetry — Mode 22 DIDs on ECU 7E0 (the prize)
No `10` (session control), `27` (security access) or `3E` (tester present) ever sent —
Car Scanner reads these in **default session, no unlock**. 33 unique DIDs:

```
220004 220005 220007 22000B 22000C 22000E 22000F
220033 220044 220047
221100 221102 221141 221145 221164 22116F
221192 221193 22119D 22119E
2211A1 2211A3 2211A6 2211E8
221200 221201 221202 221203 221204 221205 221206 221207 221208
```

STATUS — **RESOLVED, dead end for this app.** These 33 DIDs are the `[ECM]` group in
Car Scanner's honeypot enumeration (`2026-06-25 20-04-19.csv`): the range-extender ICE's
internal engine diagnostics — Engine Speed, Coolant, Misfire Cyl 1–4, Long Term Fuel
Trim, Ignition Advance, Knock Retard, Front O2, injector pulse width, A/F ratio, catalyst
temp, etc. On the REAL car (`2026-06-25 19-37-19.csv`) the entire `[ECM]` block is EMPTY
(matches our blind `221201`=NO DATA). Not battery/motor telemetry; not worth chasing.
Maybe-later only: could populate with the RE actively running, but it's engine internals.

## Standard PIDs — Mode 01 on 7DF (curated subset the profile polls)
Far richer than the 3 our StandardPidScanner kept. Includes the confirmed EV trio
(`015B` SoC, `019A` HV V/A, `01A6` odometer) plus many ICE/aux PIDs:

```
0101 0103 0104 0105 0106 0107 010B 010C 010D 010E 010F 0111 0115
011C 011F 0121 0123 012E 012F 0130 0131 0132 0133 0134 013C
0141 0142 0143 0144 0145 0146 0147 0149 014A 014C 014E
0151 0153 0155 0156 015A 015B 0161 0167 019A 01A6
```

Also probes VIN/info: `0902 0904 090A`, and `STI`/`VTI` (STN/OBDLink adapter id cmds).
```
