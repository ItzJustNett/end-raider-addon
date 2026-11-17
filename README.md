# End Raider (Meteor addon)

Meteor Client addon (MC 26.3) that raids the End together with **Baritone**. Client-side only.

## Usage
1. Install Meteor Client + Baritone for Meteor, drop `end-raider-*.jar` into `mods/`.
2. In the End, go through a gateway to the outer islands.
3. Enable module **End Raider** (category *End Raider*).

## What it does
- **Mending check** – if your pickaxe has no Mending it warns in chat; enable the module again within 20 s to continue anyway.
- **Baritone setup** – sets junk blocks (end stone, netherrack, cobble…) as throwaway blocks, enables placing, disables parkour, high place-penalty so it walks instead of bridging when possible. Original Baritone settings are restored on disable.
- **Island hopping** – scans loaded chunks, finds the current island, its farthest edge along the heading (away from 0,0), and picks the next island with the **shortest gap** (fewest blocks). If nothing is visible ahead it walks to the farthest edge and bridges straight.
- **End cities** – heads to purpur blocks when seen.
- **Low blocks** – mines end stone until `collect-to`.
- **Low pickaxe** – pillars up 3 blocks (standing on placed blocks, no airplace), places an 8-block ring around the pillar top → 3×3 roof 2 blocks above the floor, walks off, breaks the 2 support blocks, stands in the middle. Pickaxe goes to offhand (Mending), sword to hand; endermen (2.9 tall) can't get under the roof, the bot hits them. Camera moves smoothly with small random noise and looks around while idle. Continues when the pickaxe reaches `resume-durability-%`.
- **Elytra found** (item frame with elytra) – stops, boxes itself in, prints coordinates in chat.

## Build
Needs JDK 25: `./gradlew build` → `build/libs/`.
