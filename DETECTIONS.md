# SentinelAC detections

Checks use Paper/Bukkit events and server-observed movement. They are enabled by default, use per-check buffers, and notify operators. A flagged movement, hit, or placement can be cancelled or corrected. Automatic kicks and bans are disabled by default.

## Reference limits

| Area | Reference limit used | Source noted by project |
|---|---|---|
| Walk / sprint / sprint with Speed II | 4.317 / 5.612 / 7.857 blocks/s | Minecraft Wiki movement figures and movement-speed attribute |
| Ground movement | Prior momentum × friction plus ground acceleration; sprint-jump adds horizontal boost | Vanilla movement equations |
| Air movement | Prior horizontal momentum × 0.91 plus air acceleration | Vanilla movement equations |
| Gravity / terminal fall | `(dy - 0.08) × 0.98`; terminal 3.92 blocks/tick | Vanilla movement equations |
| Jump | 0.42 base vertical velocity plus Jump Boost; ordinary flat jump lasts about 12 ticks | Vanilla movement equations |
| Boat | Surface-dependent caps; blue ice is faster than packed ice | Minecraft Wiki boat figures |
| Minecart | About 8 blocks/s normal rail speed | Minecraft Wiki / feedback.minecraft.net |
| Mace | Smash bonus depends on server-observed fall distance and Density | Minecraft Wiki mace figures |
| Spear | Tier multiplier × server-observed closing speed; Lunge and mount/elytra states have separate allowances | Minecraft Wiki spear figures |

These are bounds with configured tolerance and grace periods, not a claim that every edge case has been verified on a live server.

## Checks

| Check | What it flags | Response |
|---|---|---|
| speed | Horizontal movement beyond the bound for the previous tick's ground/air state, friction, effects, and sprint jump | Cancels the move after the violation buffer |
| fly | Vertical movement beyond gravity/jump limits, excessive falling speed, or prolonged airborne hover | Cancels or corrects movement; may return the player to last ground |
| nofall | Client claims grounded while the server finds no supporting block | Cancels the move and restores server-tracked fall distance |
| timer | Sustained move rate above the tick budget, with a lag allowance | Cancels excess moves after the buffer |
| antiknockback | Server-applied knockback produces much less movement than expected | Records evidence and corrects only under its configured conditions |
| killaura | Attack ray misses the lag-expanded server hitbox, attacks through a wall, attacks multiple entities in one tick, or attacks while using an incompatible item | Cancels the hit after the relevant buffer |
| aim-assist | At least 10 of the latest 12 PvP hits are within 0.45 degrees of the target center | OP-only suspicion notice; does not cancel the hit or punish |
| hitbox | Repeated attack ray misses the server-side player hitbox after bounded ping and target-motion allowance | Cancels the hit after the buffer |
| criticals | Repeated oversized damage on a server-grounded hit beyond a conservative non-critical ceiling | Cancels the hit after the buffer |
| reach | Eye-to-hitbox distance exceeds the interaction-range allowance, with ping tolerance | Cancels the hit after the buffer |
| scaffold | Excessive placement rate, placement inconsistent with the crosshair target, or placement out of reach | Cancels placement after the buffer |
| boatfly / boatspeed | Boat hovers or rises without a valid source, or exceeds its surface allowance | Ejects the rider and corrects the vehicle position |
| vehiclespeed | Minecart or mount exceeds its configured movement allowance | Ejects the rider and corrects the vehicle position |
| macekill | Mace damage exceeds what the server-observed fall permits | Cancels the hit |
| spearkill | Spear damage exceeds the tier multiplier and server-observed speed allowance | Cancels the hit |
| invalid-action | Invalid pitch or sprint state such as insufficient food/blindness | Cancels the action |

## Client brands and operator access

On join, Paper's reported client brand is saved in `plugins/SentinelAC/client-brands.yml`. Operators receive a join notice and can use `/brandcheck <player>` to view the latest saved value. Brands are self-reported by clients or proxies, can be missing or spoofed, and are not proof of cheating.

## Known limits

- The plugin does not inspect raw network packets. Checks use the state Paper exposes through events and entity attributes.
- The aim-assist check is only a coarse precision heuristic: skilled players can trigger it, and a cheat that stays within ordinary aim patterns can evade it. It is alert-only.
- The hitbox check detects attack rays that miss the server-side box; it cannot directly inspect a modified client hitbox.
- Movement checks can need tuning for unusual server plugins, knockback, pistons, slime/bed bounces, wind charges, and lag.
- Client-brand values are not reliable proof of client software.
- Project documentation records that this build has not yet been validated on a live Paper server.
