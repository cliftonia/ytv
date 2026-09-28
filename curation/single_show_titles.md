# FAST channels whose title is the channel's own name

For the owner to decide on; nothing has been dropped. 119 FAST channels on the LIVE TV dial show, as what is on, a title that only repeats the channel's name (normalised: case, punctuation, "the", "TV", "channel"; a title that is the name with a suffix, like "AFV" on "AFV with Alfonso Ribeiro", counts; a title that adds to the name, like "Deadly Women Season 05", does not).
They are single-show channels: the show IS what is on, so the question is whether the details pane still says which episode.

Source: the committed `fast_guide.json`, title on air at its build (2026-09-28 07:03 UTC), and every airing of that title in its thirty hours. By service: samsung 97, xumo 16, rakuten 4, roku 1, plex 1. Tubi's 57 channels had no titles at all in this build (fixed by the server's guide job asking through the US relay), so they are not counted.

A description is episode-level when the airings carry different descriptions - at least four, or one for every three airings. "Airings" counts the title's slots in the window; "descs" the different descriptions among them.

| | Channels | What the details pane shows |
|---|---|---|
| Episode-level | 95 | the show as the title, and a description of the episode |
| Generic | 23 | one description for the whole channel, every airing |
| Unclear | 1 | a few descriptions over many airings |

## Generic - the description says nothing about the episode (23)

| # | Channel | Guide | Title | Airings | Descs | Description now |
|---|---|---|---|---|---|---|
| 314 | Mythical 24/7 | samsung | Mythical 24/7 | 2 | 1 | Mythical 24/7 US |
| 358 | The Walking Dead Universe | xumo | The Walking Dead Universe | 31 | 1 | Join Daryl, Michonne, the Clarks and others from the world of the… |
| 703 | Family Feud Classic | xumo | Family Feud | 68 | 1 | Family Feud is an American television game show where two families… |
| 705 | Let's Make A Deal Classic | samsung | Let's Make a Deal | 13 | 1 | Contestants dressed in outrageous costumes trade items for either… |
| 707 | The Price is Right: The Barker Era | xumo | The Price Is Right | 31 | 1 | Contestants bid for prizes then compete for fabulous showcases. |
| 709 | Tipping Point | samsung | Tipping Point | 7 | 1 | Ben Shephard hosts the quiz show in which four players take on a… |
| 1101 | Stingray Flashback 70s | samsung | Stingray Flashback 70's | 4 | 1 | Relive the era of platforms, Studio 54, flower power, and all the… |
| 1102 | Stingray Jukebox Oldies | samsung | Stingray Jukebox Oldies | 4 | 1 | Relive the carefree, lively days of early rock 'n' roll with the… |
| 1111 | Stingray Remember the 80s | samsung | Stingray Remember the 80's | 4 | 1 | Relive the decade of perms and shoulder pads! The unmistakable… |
| 1121 | Stingray Nothin' But 90s | samsung | Stingray Nothin' But '90s | 4 | 1 | The ‘90s was a decade like no other. Grunge, pop, hip-hop, and… |
| 1131 | Stingray Classic Rock | samsung | Stingray Classic Rock | 4 | 1 | Relive the wonders of classic rock, the sound that revolutionized… |
| 1141 | Stingray Country Greats | samsung | Stingray Country Greats | 4 | 1 | Before today's Hot Country and the New Country trends of the ‘90s… |
| 1142 | Stingray Hot Country | samsung | Stingray Hot Country | 4 | 1 | Today's country music is on fire, and it keeps getting hotter. This… |
| 1150 | Stingray Hip Hop R&B | samsung | Stingray Hip-Hop/R&B | 4 | 1 | Experience the freshest beats by the hottest artists on today's… |
| 1164 | Stingray Euro Hits | samsung | Stingray Euro Hits | 4 | 1 | Enjoy Europe's most popular and current music, including hits by… |
| 1165 | Stingray Greatest Hits | samsung | Stingray Greatest Hits | 4 | 1 | Enjoy this collection of the greatest chart-toppers from the ‘70s to… |
| 1166 | Stingray Soft Hits | samsung | Stingray Soft Hits | 4 | 1 | Need a break? These relaxing, easy-listening pop songs from the… |
| 1167 | Stingray Today's KPOP | samsung | Stingray Today's K-Pop | 4 | 1 | Tune in to the captivating mega hits by today's K-pop superstars.… |
| 1182 | Stingray Smooth Jazz | samsung | Stingray Smooth Jazz | 4 | 1 | Explore the hottest contemporary jazz artists in a smooth-flowing… |
| 1201 | Stingray Cozy Cafe | roku | Stingray Cozy Cafe | 31 | 1 | Welcome to Cozy Café, where trendy coffee house visuals blend with… |
| 1202 | Stingray Easy Listening | samsung | Stingray Easy Listening | 4 | 1 | Sit back and relax to this selection of instrumental music. It's the… |
| 1362 | Mark Rober TV | samsung | Mark Rober TV | 6 | 1 | Former NASA & Apple engineer. Current YouTuber and friend of science. |
| 1523 | Hard Knocks | plex | Hard Knocks | 17 | 1 | Hard Knocks is your go-to destination for MMA and Fighter action! |

## Unclear - a few descriptions rotating (1)

| # | Channel | Guide | Title | Airings | Descs | Description now |
|---|---|---|---|---|---|---|
| 845 | ducktv | samsung | ducktv | 24 | 3 | ducktv favorites are the most popular ducktv shows, representing… |

## Episode-level - the description tells the episode (95)

| # | Channel | Guide | Title | Airings | Descs | Description now |
|---|---|---|---|---|---|---|
| 300 | AFV with Alfonso Ribeiro | samsung | AFV | 8 | 8 | Catch a special Halloween episode of “America's Funniest Home… |
| 308 | Conan O'Brien TV | samsung | Conan O'Brien | 11 | 11 | In which Andy Richter demonstrates his otherworldy skills as a… |
| 312 | Mr Bean Live Action | samsung | Mr Bean Live Action | 11 | 11 | Mr Bean resuscitates a heart attack victim at the bus stop. |
| 316 | Portlandia | xumo | Portlandia | 67 | 66 | Spyke gets his old band back together; podcasters investigate a… |
| 319 | Smosh | samsung | Smosh | 5 | 5 | Shayne and Amanda talk to Chanse about some of the worst jobs… |
| 320 | The Try Guys | samsung | The Try Guys | 7 | 7 | We decided to re-live the worst of times and recreate our awkward… |
| 340 | Death Valley Days | samsung | Death Valley Days | 12 | 12 | A detective disguises himself as a Mexican to clear himself of a… |
| 341 | The Lone Ranger | xumo | The Lone Ranger | 67 | 67 | A man who has sworn to murder the Lone Ranger enlists the aid of a… |
| 357 | Van Helsing | samsung | Van Helsing | 8 | 8 | On a distant island, Vanessa and Scarlett battle the Third Elder… |
| 364 | Designated Survivor | xumo | Designated Survivor | 36 | 36 | Kirkman is a Commander-in-Chief determined to capture the terrorists… |
| 382 | Lassie | xumo | Lassie | 66 | 66 | Timmy and Lassie run into difficulty after agreeing to take care of… |
| 383 | Little House on the Prairie | xumo | Little House on the Prairie | 34 | 34 | Charles sees two children (Jason Bateman, Missy Francis) lose their… |
| 391 | Degrassi | samsung | Degrassi | 12 | 12 | Ashley becomes very controlling while she and Jimmy work toward… |
| 394 | Heartland | samsung | Heartland | 7 | 7 | Ty is forced to ask his stepfather for help. |
| 410 | 21 Jump Street | samsung | 21 Jump Street | 8 | 7 | The Jump Street team (Johnny Depp, Holly Robinson, Dustin Nguyen,… |
| 412 | Alfred Hitchcock Presents | xumo | Alfred Hitchcock Presents | 52 | 52 | A woman (Judith Evelyn) is suspected of murdering her husband, who… |
| 421 | Murder She Wrote | xumo | Murder, She Wrote | 28 | 27 | Jessica helps an inexperienced sheriff investigate a murder in a… |
| 439 | Little Women LA | samsung | Little Women: LA | 7 | 7 | Matt makes amends, Christy's marriage struggles, Amanda confronts… |
| 442 | The Osbournes | samsung | The Osbournes | 14 | 14 | Ozzy releases his first solo album in six years. |
| 451 | Alone By History | samsung | Alone | 7 | 7 | The hikers struggle to make the final push to their teammates. |
| 452 | America's Got Talent | samsung | Americas Got Talent | 5 | 5 | The semi-finalists perform for America's vote to move on to the… |
| 453 | American Idol | samsung | American Idol | 4 | 4 | As the competition draws to a close, the final 3 are announced. |
| 454 | American Ninja Warrior | xumo | American Ninja Warrior | 19 | 18 | In the last night of the Las Vegas finals, competitors tackle… |
| 456 | Bring It! | samsung | Bring It! | 6 | 6 | Tensions run high as the Dancing Dolls go head-to-head with their… |
| 458 | Forged In Fire | samsung | Forged in Fire | 7 | 7 | Round Four of the Battle of the Branches sets sail. |
| 462 | Robot Wars by Mech+ | samsung | Robot Wars | 7 | 7 | The last stage before the Grand Finals, and the pressure is on. With… |
| 463 | Survivor | samsung | Survivor | 5 | 5 | The Contenders continue their trend of winning Immunity Challenges,… |
| 471 | Cheaters | samsung | Cheaters | 14 | 14 | Niecy Wolfe discovers her boyfriend raining lies about their… |
| 480 | American Pickers by History | samsung | American Pickers | 7 | 7 | It's tattoo mania in New York. |
| 483 | Operation Repo | samsung | Operation Repo | 12 | 12 | Lyndah and Sonia are forced to use pepper spray on a car owner who… |
| 484 | Pawn Stars | samsung | Pawn Stars | 9 | 9 | The Pawn Stars have an opportunity. |
| 486 | Storage Wars LA | samsung | Storage Wars | 13 | 13 | Dan and Laura Dotson discuss "Pay the Lady" origins with Barry and… |
| 490 | Bondi Rescue | samsung | Bondi Rescue | 13 | 13 | A woman is found lifeless between the flags, she's not breathing and… |
| 491 | Duck Dynasty | rakuten | Duck Dynasty | 67 | 27 | The Robertson brothers debate which of their wives makes the best… |
| 492 | Hoarders | samsung | Hoarders | 7 | 7 | In this episode of Hoarders we check in with five hoarders from past… |
| 493 | Intervention | samsung | Intervention | 7 | 7 | Although she has two loving children and an adoring husband. |
| 496 | Untold Stories of the ER | samsung | Untold Stories of the ER | 36 | 31 | A patient is brought into the ER after being shot in the thigh. |
| 500 | Cutlers Court | samsung | Cutlers Court | 15 | 15 | A dramatic dispute over infidelity, fatherhood and responsibility… |
| 501 | Divorce Court | samsung | Divorce Court | 15 | 13 | Jeremy says Beth's drug problem resulted in her lying, having… |
| 600 | Are We There Yet? | samsung | Are We There Yet? | 14 | 14 | Nick and others enter a contest to win a house. |
| 611 | Leave It to Beaver | xumo | Leave It to Beaver | 66 | 66 | Beaver thinks that the new neighbor's wife has a crush on him. |
| 614 | Saved by the Bell | xumo | Saved by the Bell | 74 | 72 | A student argument helps a teacher resolve a problem with a longtime… |
| 701 | Celebrity Name Game | xumo | Celebrity Name Game | 76 | 34 | Celebrity guests Cameron Mathison and Jamie-Lynn Sigler team up with… |
| 809 | Rainbow Ruby | samsung | Rainbow Ruby | 13 | 12 | Ling Ling takes Rainbow Ruby to Ellie's cabin where she meets… |
| 810 | Rev and Roll | samsung | Rev and Roll | 13 | 13 | After many years of working hard on Accelerator Acres, Tilly the… |
| 811 | Slugterra | samsung | Slugterra | 12 | 11 | A fun trip to the mall cavern turns un-deadly when the place is over… |
| 814 | Strawberry Shortcake | samsung | Strawberry Shortcake | 12 | 12 | While rehearsing a song alone in her cafe, Strawberry is interrupted… |
| 841 | Baby Einstein | samsung | Baby Einstein | 10 | 9 | Learn all about music and rhythm in the world around us! Discover… |
| 843 | Blippi | samsung | Blippi | 10 | 9 | Blippi enjoys a day of playtime at Billy Beez in Anaheim, CA. |
| 844 | Caillou | samsung | Caillou | 12 | 11 | Caillou is playing with the fridge magnets and learns lots of things… |
| 855 | Teletubbies | samsung | Teletubbies | 11 | 11 | Teletubbies love dancing, but they love children even more. So… |
| 903 | Hunter x Hunter | samsung | Hunter X Hunter | 12 | 12 | The game Greed Island is finally on the auction block Gon and Killua… |
| 905 | JoJo's Bizarre Adventure | samsung | JoJo's Bizarre Adventure | 12 | 12 | Stroheim returns from the brink as a cyborg designed to secure the… |
| 906 | Naruto | samsung | Naruto | 13 | 12 | With Rock Lee there to take on Kimimaro, Naruto is free to chase… |
| 909 | Pokémon | samsung | Pokémon | 13 | 13 | Ash puts his skills to the test against Navel Island Gym Leader Danny. |
| 911 | Yu Gi Oh | samsung | Yu-Gi-Oh | 11 | 11 | A girl claims Grandpa stole a powerful card from her family long ago. |
| 1003 | Cook's Country Channel | xumo | Cook's Country | 36 | 35 | Milk Chocolate Cheesecake; Swiss Hazelnut Cake; top picks for cake… |
| 1008 | Hot Ones | samsung | Hot Ones | 13 | 13 | Comedian Nick Kroll's cast of sketch comedy characters includes… |
| 1022 | Great British Menu | samsung | Great British Menu | 10 | 10 | Today one chef will go home and today the veteran judge finds… |
| 1023 | Kitchen Nightmares | samsung | Kitchen Nightmares | 7 | 7 | Ramsay travels to Boston to help a restaurant owner. |
| 1030 | A New Life In The Sun | samsung | A New Life In The Sun | 7 | 7 | A family from the Midlands gamble an inheritance on a diving school… |
| 1036 | Million Dollar Dream Home | samsung | Million Dollar Dream Home | 12 | 12 | Kathleen, a Hollywood producer, hunts for her dream home in the… |
| 1042 | Tiny House Nation | samsung | Tiny House Nation | 7 | 7 | Tommy and Katie are world-class triathletes. |
| 1050 | Antiques Roadshow PBS | xumo | Antiques Roadshow | 29 | 29 | A 1912 portrait by Charles Courtney Curran; Porfirio Salinas… |
| 1051 | Antiques Roadshow UK | samsung | Antiques Roadshow | 5 | 5 | From the Royal William Yard in Plymouth, finds include doorknobs… |
| 1052 | Cash in the Attic | samsung | Cash in the Attic | 8 | 8 | Lynne Cornish and her daughter Debbie call in the team to sell some… |
| 1304 | I Survived | samsung | I Survived | 7 | 7 | A teenage girl is seduced by a man and brainwashed. |
| 1315 | Jack Hanna | samsung | Jack Hanna | 12 | 12 | Jack Hanna's family visits rheas and chinchillas, sloths, and feed… |
| 1320 | River Monsters | samsung | River Monsters | 2 | 2 | It was supposed to be the safest boat in the Amazon, but when Sobral… |
| 1330 | Dog Whisperer | samsung | Dog Whisperer | 6 | 6 | A German shepherd and Welsh corgi mix has dog aggression a blind Lab… |
| 1332 | Lucky Dog | samsung | Lucky Dog | 14 | 14 | A Maltese mix appears to be an ideal lapdog for a senior, but the… |
| 1347 | Modern Marvels | samsung | Modern Marvels | 6 | 6 | Chaos in Guadalajara, Mexico, when the city streets explode |
| 1371 | Ancient Aliens | samsung | Ancient Aliens | 7 | 7 | Native peoples across North America tell stories of the Star… |
| 1372 | The Curse of Oak Island | samsung | The Curse of Oak Island | 7 | 7 | Gary and Rick uncover unprecedented finds in the swamp. |
| 1382 | The UnXplained with William Shatner | samsung | The UnXplained | 7 | 7 | Can scientists find the secret to unlocking these remarkable gifts? |
| 1390 | ABC 20/20 | samsung | 20/20 | 4 | 4 | 11/04/22: Decades after Girl Scouts are murdered, police revisit the… |
| 1397 | Evidence of Evil | samsung | Evidence of Evil | 7 | 7 | The Consummate Murder |
| 1410 | World’s Most Evil Killers | samsung | World's Most Evil Killers | 7 | 7 | A cult killer portrayed himself as a pillar of the community, but… |
| 1420 | Bloodline Detectives | samsung | Bloodline Detectives | 7 | 7 | Murder on Edgewater Creek Bridge |
| 1421 | BuzzFeed Unsolved | samsung | BuzzFeed Unsolved | 8 | 8 | Is this historic house of God really home to a demonic force? |
| 1423 | Dr. G Medical Examiner | samsung | Dr. G: Medical Examiner | 7 | 7 | A woman dies five years after a brutal mugging a man dies after… |
| 1424 | The First 48 | samsung | The First 48 | 7 | 7 | Two men in Miami are gunned down in a bloody turf war. |
| 1425 | Forensic Files | samsung | Forensic Files | 12 | 12 | A California teen went missing. Police suspected she'd run away… |
| 1426 | Medical Detectives | samsung | Medical Detectives | 68 | 16 | When a Yale student mysteriously vanishes days before her wedding,… |
| 1428 | Unsolved Mysteries | samsung | Unsolved Mysteries | 7 | 7 | This episode includes: Black Hope Curse, Pts. 1 & 2, Con Artist Cop… |
| 1430 | 60 Days in Jail | samsung | 60 Days In | 6 | 6 | After an emotional breakdown, Alan is not sure he can continue. |
| 1431 | Cops | rakuten | Cops | 72 | 70 | An armed trespasser uses a young dog as a shield. A woman under the… |
| 1435 | Dog The Bounty Hunter | rakuten | Dog The Bounty Hunter | 64 | 25 | Out of patience, Leland calls in the Big Dog. |
| 1436 | Jail | rakuten | Jail | 74 | 72 | In Las Vegas, NV a man who insists he paid for a speeding ticket… |
| 1460 | Ax Men | samsung | Ax Men | 7 | 7 | As the season moves on, everyone's going to greater lengths to stay… |
| 1462 | Ice Road Truckers | samsung | Ice Road Truckers | 7 | 7 | As the ice roads melt away, Polar and VP Express are neck and neck. |
| 1463 | MeatEater | samsung | MeatEater | 9 | 9 | Steven Rinella heads into the mountainous desert backcountry of west… |
| 1464 | Mountain Men | samsung | Mountain Men | 6 | 6 | In Montana, Tom Oar & Sean McAfee trap Jake Herak & Anika Ward… |
| 1471 | Swamp People | samsung | Swamp People | 6 | 6 | Willie competes for marksman title Don tests Swamp Juice Ronnie… |
| 1593 | Strongman Champions League | samsung | Strongman Champions League | 7 | 7 | Strongman Champions League: World Record Breakers 2024 kicks off… |
