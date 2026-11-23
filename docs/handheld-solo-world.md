# Handheld solo world

The optional solo build runs inside the managed LandSandBoat server. One FFXI client connects with the existing xiloader. Market activity, Conquest and companion behavior use the local database and server. The player keeps the world's configured level cap and expansions.

## Update an existing world

1. Install the signed 0.8.0 APK over the standard LSB Android app. Finish the current session and stop the client and managed server. Installing the APK alone leaves the active server selected.
2. Open the **Build** tab. If the selected source already shows LandSandBoat commit `6d5a5137024e21602e37032f6e55a682971329be`, reuse it. Otherwise choose **Fetch supported solo source**. Fetching source and missing build dependencies requires connectivity during preparation.
3. In step 2, check **Build with handheld solo features** and tap **Build fetched source with jemalloc**. Wait for the completed build to show **Handheld solo features included**.
4. In step 3, choose **Keep current player data · copy and update**, then **Prepare database and stage this build**. Check the character count, database, and expected client in step 4. Check and deploy that staged pair, then start the managed server.
5. Sign in with your existing character. If you return to the previous deployment, its database contains the progress made before this switch. Each deployment keeps its own database.

## Playing

| Feature | What to try |
|---|---|
| Crafting | Train all eight synthesis crafts on one character. Normal guild rank tests still apply. The build retains the world's common craft threshold and gives enough specialization points to reach 110 in each craft. |
| Gathering and fishing | Use regular tools at a mining, logging, harvesting or excavation point. The solo defaults raise low obtain chances to 80% and halve the underlying tool-break rate. Fishing is enabled through the native rod, bait and fishing minigame. |
| Treasure caskets | Fight normal monsters in Ghelsba Outpost, Giddeus, Palborough Mines or another supported area. Extra chests use the configured casket drop rate, are claimable by the winning party, and last three minutes. Fourteen additional zones are covered. Stock-zone brown chests retain their lock puzzles. |
| Conquest | Close the world and return later. New solo deployments adopt the current standings. Six-hour increments of offline time advance regional influence, and missed Monday 00:00 JST tallies change ownership. A start processes up to four weeks. |
| Auction house | Use a normal AH counter. **LSB Provisioner** rotates 92 supplies, weapons, armor, instruments and scrolls in small batches. Open **Price History** for an empty catalogue item to request a listing. A Bronze Sword is 300 gil. Up to eight immediate requests are filled per five-minute interval. Sell a reasonably priced, tradeable item and check your delivery box after it has had time to clear. Market activity uses local prices and the ordinary LSB gil payout. |
| Personal squad | In an outdoor area where Trusts are allowed, enter `!squad new Mira WHM`, `!squad role 1 healer`, and `!squad call 1`. Fight an EXP-bearing monster, then use `!squad levels 1`. Dismiss with `!squad dismiss`. After a fresh login call the same slot again. |

Six companions may be saved per character. One can be active at a time. New mates start at level 1 by default. An explicit starting level can be as high as 12 within your main-job level. Each mate keeps its own job XP through level 75 and supports the 18 jobs from Warrior through Puppetmaster. New levels take effect on the next call. Use `!squad equip 1` while the mate is dismissed to save eligible base items from the player's equipped gear. Gear is saved per job. `!squad help` lists the other commands. Pet jobs use companion combat behavior; independently summoned avatars, wyverns, familiars and automatons are outside this version.

For a device check, keep the same client settings and repeat one outdoor fight solo and with one mate. Confirm the mate survives zoning or can be recalled, then stop and reopen the server to verify the character, companion XP, gear and auction delivery.
