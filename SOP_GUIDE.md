# Warehouse SOP Cards

When a worker reports a situation, the assistant proactively offers the matching guidance—even if the worker did not ask for help. Give the useful next step first, then ask one clarification at a time.

This is the human-readable review copy. The structured retrieval dataset is [sop_cards.jsonl](seed_data/sop_cards.jsonl), with one record per card. Embed each record's `embedding_text` and return its structured fields to the agent. Shared response rules are in [sop_policy.json](seed_data/sop_policy.json). Retrieve using the situation and intent, not just the product name. Trigger phrases are examples, not exact-match requirements.

Draft for the demo. Report destinations below are team roles; the retailer must supply actual contacts, approved procedures, and reporting channels. Report creation and notification are separate actions: never claim someone was notified unless delivery is confirmed.

Worker safety comes before the damage report for every product. Give hygiene or first-aid advice when contact, contamination, injury, or symptoms are reported; product damage alone does not imply an injury. Ask one short safety question when needed, and offer a first-aider, first-aid kit (medical box), wash station, or eyewash location. Never delay immediate care to identify a SKU or complete a form.

## SOP-01 — Damaged clothing, shoes, or accessories

- **When:** T-shirt stock looks damaged; torn hoodie; stained clothes; damaged shoes; broken wallet zip.
- **Proactive guidance:** Keep the affected items out of picking. If handling is safe, follow the site's damage-hold procedure. Do not return them to sellable stock.
- **Worker safety:** If handling left hands dirty, wash with soap and running water for at least 20 seconds before eating or touching the face. If there is a cut, rash, or unknown contamination, stop handling and get first-aid advice; ordinary handwashing is not treatment for chemical exposure.
- **File with:** Quality team or floor supervisor, as a damaged-stock report.
- **Ask next:** "What damage can you see?" Then clarify the product variant, location, and quantity if known.
- **Get help:** If there is contamination, liquid, sharp material, or an unstable load, stop handling it and involve the safety team.

## SOP-02 — Missing stock or wrong stock count

- **When:** Empty bin; missing sneakers; fewer hoodies than the recorded count; wrong size in a bin.
- **Proactive guidance:** Check the exact variant and recorded location. Log the difference between the stored count and what the worker observed; do not automatically change inventory.
- **File with:** Inventory-control team, as a stock discrepancy.
- **Ask next:** "Which product and bin are you checking?" Then ask how many are present, if known.
- **Get help:** If the stock cannot be found or the location is unsafe to access, involve the supervisor. Do not ask the worker to climb or enter a restricted area.

## SOP-03 — Damaged or leaking perfume stock

- **When:** Damage in the perfume section; North one leaking; wet fragrance carton; broken perfume packaging.
- **Proactive guidance:** Pause handling. If liquid or broken bottles are present, move clear and do not touch or clean the spill. Trained responders use the product safety data sheet and site spill procedure.
- **Worker safety:** If perfume contacted skin, start rinsing with running water immediately for at least 15 minutes and get first-aid/poison-center advice. If splashed in the eyes, rinse immediately for 15–20 minutes; do not rub. Offer the verified wash/eyewash location without delaying rinsing. Serious symptoms require emergency help.
- **File with:** Safety/spill-response team for a leak; quality team for packaging-only damage.
- **Ask next:** From safety: "Is there leaking liquid or broken glass?" Then clarify the location and product if already known.
- **Get help:** Injury, breathing trouble, smoke, or suspected gas requires the site's emergency route; do not wait to finish the report.

## SOP-04 — Broken glass on the floor

- **When:** Glass shards; broken fragrance bottle on the floor; shattered bottle; someone may have been cut.
- **Proactive guidance:** Keep clear of the glass and warn nearby workers from a safe position. Do not pick it up by hand or walk through it. Leave cleanup to trained, authorized staff.
- **Worker safety:** If cut, get a first-aider. For bleeding without embedded glass, apply firm pressure with a clean dressing. Do not remove embedded glass or press directly on it; apply pressure around it. Heavy or uncontrolled bleeding requires emergency help; do not walk off to search for a medical box.
- **File with:** Safety team, as a broken-glass hazard; first-aid team if someone is injured.
- **Ask next:** "Is anyone hurt?" Then ask where the glass is. If liquid is also present, use the spill guidance.
- **Get help:** Serious injury or immediate danger requires emergency help. No photo or SKU is needed before getting help.

## SOP-05 — Damaged boxes, pallets, or leaning stock

- **When:** Crushed carton; split pallet; unstable stack; stock leaning or falling.
- **Proactive guidance:** Pause the affected pick or move. Keep clear of an unstable load. Do not pull out supporting boxes, steady the load by hand, or climb the stack.
- **Worker safety:** Wash dirty hands after handling packaging. For splinters, cuts, or a crush injury, stop handling and get a first-aider. Do not dig out embedded objects. Offer a verified first-aid-kit location for minor injuries; serious injury takes the emergency route.
- **File with:** Floor supervisor or material-handling team; safety team for unstable loads or rack damage.
- **Ask next:** "Where is the damaged box or load?" Then clarify what damage was already observed.
- **Get help:** If it may fall or someone is injured, use the site's emergency route. Authorized staff decide when handling can resume.

## SOP-06 — Odd or unknown smell

- **When:** Bad smell; unfamiliar odor; strange chemical smell; unexplained smell near stock.
- **Proactive guidance:** Move away from the affected area and report from a safe place. Do not follow the smell, sniff containers, or assume perfume explains it.
- **Worker safety:** If fumes cause irritation or dizziness, move to safe fresh air and get first-aid help. Do not enter the affected area to assist someone; trained responders must handle unsafe rescue. Breathing difficulty or collapse requires emergency help.
- **File with:** Safety team, as an unknown-odor report.
- **Ask next:** Once safe: "Is anyone feeling unwell?" Then ask where the smell was noticed.
- **Get help:** Burning/smoke cues use SOP-09. Suspected gas uses SOP-10. Serious symptoms require emergency help. An unknown source is enough to ask for assistance.

## SOP-07 — Temperature, cooling, or ventilation issue

- **When:** Warehouse too hot or cold; cooling not working; heating problem; poor airflow; worker feeling unwell.
- **Proactive guidance:** Report the affected area to maintenance. If conditions feel unsafe or someone feels unwell, pause work and move to a safe area with suitable conditions. Do not open electrical panels or override equipment.
- **Worker safety:** Offer the first-aider and a verified safe rest area when someone feels unwell. A medical box does not replace medical help for heat/cold illness. Severe symptoms take priority over location lookup or reporting.
- **File with:** Maintenance and floor supervisor; first-aid team for symptoms.
- **Ask next:** "Is anyone feeling unwell?" Then ask which area is affected.
- **Get help:** Confusion, collapse, seizure, or breathing difficulty requires emergency medical help. For suspected heat illness, move to a safe cooler area and start cooling while help is arranged; do not give an unconscious person anything to drink.

## SOP-08 — Loading-dock problem

- **When:** Damaged dock plate; trailer moving unexpectedly; restraint fault; unsafe gap; blocked dock.
- **Proactive guidance:** Stop the affected loading task and move clear of the dock edge and vehicle. Do not enter an unsecured trailer, bypass restraints, or improvise a repair.
- **Worker safety:** For an injury, contact a first-aider from safety. Do not enter a moving-vehicle area to help. Serious falls or crush injuries require emergency help; do not move the injured person unless remaining there presents immediate danger.
- **File with:** Dock lead and maintenance.
- **Ask next:** "Which dock is affected?" Then ask what fault was noticed.
- **Get help:** Injury or immediate danger requires emergency help. Authorized dock staff must clear the dock before work resumes.

## SOP-09 — Smoke, burning smell, or fire

- **When:** Smoke; burning smell; visible flames; stock or equipment appears to be burning.
- **Proactive guidance:** Move away from danger and follow the site's fire emergency procedure. Raise the alarm when safe. Do not investigate, enter smoke, or stay to finish a report.
- **Worker safety:** Smoke exposure, burns, or breathing problems need first-aid/medical assessment from safety. Do not return for a medical box. Serious symptoms require emergency help.
- **File with:** Site emergency team and emergency services through the approved site route.
- **Ask next:** Nothing before protective action. Record the location only after the worker is safe.
- **Get help:** Emergency help takes priority over chat. Do not return until the designated authority permits it.

## SOP-10 — Gas smell or suspected gas leak

- **When:** Smell of gas; possible gas leak; gas-like odor; hissing with suspected gas.
- **Proactive guidance:** Leave the suspected gas area immediately on foot. Stop using this device there. Do not operate switches, use phones/radios, start vehicles, create flames, or investigate.
- **Worker safety:** If affected by fumes, seek first-aid/medical help from a safe location. Breathing difficulty, collapse, or loss of consciousness requires emergency help. Do not stay in the area to find a medical box or continue chatting.
- **File with:** Emergency services and the site emergency team from a safe location.
- **Ask next:** Nothing while the worker is in the suspected gas area. Record the location after they are safe.
- **Get help:** Contact emergency help from a safe location. Do not return until the designated authority permits it.

## CARE-01 — Dirty hands after handling damaged stock

- **When:** Dirty hands after handling damaged clothing, footwear, bags, wallets, socks, cartons, or pallets; worker asks whether to wash hands.
- **Proactive guidance:** Wash with soap and running water for at least 20 seconds, rinse, and dry. Avoid touching the face or eating with dirty hands. Hand sanitizer does not reliably remove all chemicals.
- **Ask next:** "Did you only get dirt on your hands, or was there liquid contact or an injury?" Skip this question if exposure or injury is already known.
- **Get help:** Chemical contact, cuts, or persistent irritation needs the matching exposure/injury guidance and a first-aider.
- **Source:** [CDC hand hygiene guidance](https://www.cdc.gov/clean-hands/faq/).

## CARE-02 — Perfume on skin or in eyes

- **When:** Perfume on hands or skin; burning skin; fragrance splashed into eyes.
- **Proactive guidance:** For skin contact, immediately remove contaminated clothing and rinse exposed skin with plenty of running water for at least 15 minutes. Mild soap can help remove residue. For an eye splash, immediately rinse with room-temperature water for 15–20 minutes; remove contact lenses if possible without delaying rinsing. Do not rub eyes, use sanitizer on exposed skin, or try to neutralize the substance.
- **Ask next:** After rinsing has started: "Was it your skin or your eyes?" Skip if already known. Identify the product only when safe; follow its verified safety data sheet and professional advice.
- **Get help:** Contact a first-aider and the configured poison-advice service. Severe pain or vision changes need urgent medical care; breathing difficulty, collapse, or seizure requires emergency help. Do not wait for symptoms before rinsing.
- **Source:** [Poison Control exposure first aid](https://www.poison.org/first-aid-for-poisonings).

## CARE-03 — Cut or bleeding after handling stock

- **When:** Cut by a bottle, damaged packaging, or sharp product part; bleeding hand; glass embedded in a wound.
- **Proactive guidance:** Stop handling and get a first-aider. If nothing is embedded, apply firm direct pressure using a clean dressing. If glass or another object is embedded, leave it in place and apply pressure around it, not onto it. Wear protective gloves when helping another person if available.
- **Ask next:** "Is bleeding heavy or not stopping?" Then clarify whether anything is embedded, if not already reported.
- **Get help:** Heavy, continuous, spurting, or uncontrolled bleeding requires emergency help immediately. Ask someone else to bring supplies; do not delay pressure while searching for a kit. Do not prescribe medication or improvised treatment.
- **Source:** [Red Cross bleeding first aid](https://www.redcross.org/take-a-class/resources/learn-first-aid/bleeding-life-threatening-external).

## CARE-04 — Find a medical box, first-aider, or wash station

- **When:** "Where is the medical box?"; first-aid kit needed; worker needs a wash station, eyewash, or first-aider following damage or exposure.
- **Proactive guidance:** Use verified site records to return the resource name, exact location, and a useful landmark. Give a nearest location only when the worker's location and site layout support that claim. Never invent a location from a product's storage bin.
- **Ask next:** "Which area are you in?" Only ask when moving or chatting is safe. If the location is already known, use it.
- **Get help:** If no verified location is available, say so and direct the worker to the floor supervisor or first-aider. Immediate rinsing, bleeding control, and emergency help must not wait for lookup. An injured worker may need someone to bring the kit.
- **Site data needed:** Resource type, name, location/landmark, first-aider contact, and verification date. Actual locations are not yet supplied for this PoC.

## HELP-01 — Report an issue for a coworker

- **When:** "My coworker got cut"; "Someone feels dizzy"; "A worker collapsed"; reporting damage or exposure on someone else's behalf.
- **Proactive guidance:** Check that you can stay safely nearby. For collapse, abnormal or absent breathing, severe breathing difficulty, or heavy bleeding, get emergency help immediately. Do not enter a hazardous area to rescue someone. Notify a first-aider and follow the emergency dispatcher's instructions.
- **Ask next:** "Are they responsive and breathing normally?" Ask only from safety, without delaying an emergency call. For routine incidents, ask what happened and where; the person's name is optional.
- **File with:** First-aid/safety team for injury or exposure; quality or inventory team for product-only issues. Record that the worker is reporting on behalf of someone else, and distinguish observed facts from information heard from others.
- **Get help:** Ask a specific nearby person to call emergency services or bring a first-aider while you remain with the affected worker if safe. Do not move someone with a serious injury unless remaining there presents immediate danger. Follow dispatcher guidance for first aid or CPR; do not invent medical instructions.

## HELP-02 — Call 911 or the site's emergency number

- **When:** "How do I call 911?"; "Get an ambulance"; serious injury, collapse, breathing difficulty, fire, or suspected gas leak.
- **Proactive guidance:** From a safe location, dial 911 on a phone capable of making calls where 911 is the local emergency number. Elsewhere, use the verified local emergency number. Do not wait to complete a report or obtain supervisor approval. In a suspected gas area, leave before using a phone or this device.
- **What to say:** "We need [medical/fire] help at [verified street address]. The incident is in [warehouse area/dock]. [Describe what happened and the person's condition]. My callback number is [number]." Say what you know; missing details must not delay the call. Follow the dispatcher's questions and instructions, and stay on the line until told to hang up.
- **Get help:** If the Zebra device cannot make a voice call or a call fails, use another available phone from safety and activate the site's emergency reporting method. Never claim the assistant called or reached responders without confirmation. Internal notification does not replace an emergency call.
- **Site data needed:** Country, emergency number, verified street address, building/area names, and responder access information. Do not assume a storage-bin code is enough for responders.
- **Demo rule:** Simulate calls in the PoC; do not place live 911 calls during demonstrations.
- **Source:** [National 911 Program calling guidance](https://www.911.gov/calling-911/frequently-asked-questions/).

## HELP-03 — Find an exit or evacuation assembly point

- **When:** "Where is the exit?"; "How do we get out?"; evacuation alarm; exit blocked; "Where should we meet outside?"
- **Proactive guidance:** Follow the site's evacuation instructions and marked exit routes away from danger. In a fire, do not use ordinary elevators. Go to the designated assembly point and report to the person taking attendance. Do not return for stock, belongings, or a medical box.
- **Location lookup:** Use the verified site exit map and the worker's known area. Show the exit name, approved route, and assembly point only when recorded. A map cannot confirm that a route is currently clear; account for reported smoke, gas, or obstructions. Never invent directions or call an exit the nearest without supporting site layout data.
- **Ask next:** If safe and the area is unknown: "Which area are you in?" Do not keep a worker chatting instead of leaving. If no verified route is available, tell them to follow marked exits and the site evacuation warden's instructions.
- **Get help:** For a blocked route, use another designated route away from danger. If trapped or unable to evacuate, contact emergency services from safety and give your location; do not cross smoke or a hazardous area. For workers needing assistance, follow the site's assisted-evacuation plan and alert the designated warden; do not improvise a hazardous rescue.
- **Site data needed:** Approved exit routes, alternate routes, assembly points, evacuation-warden contacts, and assisted-evacuation arrangements. Actual exit locations have not yet been supplied.
- **Source:** [OSHA emergency preparedness and evacuation guidance](https://www.osha.gov/emergency-preparedness/getting-started).

## SOP-11 — Replenishment request and stock readiness

- **When:** We need more sneakers; Restock this perfume variant; Add ten backpacks; Request replenishment; What should I check before new stock is received?.
- **Proactive guidance:** Confirm the exact product, variant, and additional quantity before submitting the request. A destination is not required. When replacement stock arrives, authorized staff check that the product, variant, quantity, and packaging match the request, complete the demo stock-onboarding and registration checks, and record the receipt before stock is made available. Creating a request does not change inventory.
- **Worker safety:** If new stock is leaking, damaged, or unstable, pause handling and use the matching damage or hazard procedure. A restock request alone does not establish that a hazard is present.
- **File with:** Inventory-control or replenishment team, as a replenishment request after worker confirmation. This is a demo role, not a configured delivery channel.
- **Ask next:** Ask only for missing product, variant, or positive additional quantity, one question at a time. Do not ask for a destination. Do not repeat fields already established.
- **Get help:** For a variant absent from the catalog, clarify or offer verified catalog alternatives without substituting stock silently. For an observed hazard, give protective guidance before continuing the request.
- **Short demo response:** When stock arrives, check the product, variant, quantity, and packaging. Authorized staff must complete receiving and stock registration before it becomes available.
- **Demo rule:** Synthetic procedure authored for this proof of concept; not a retailer-approved receiving process. Receiving, stock registration, inventory changes, task assignment, and notifications are not performed merely by showing this guidance.
- **Source:** Synthetic workflow agreed in [worker stories](user_stories.md).

## CARE-05 — Worker coughing, feverish, or feeling unwell

- **When:** My teammate is coughing a lot; A worker has a cold; My coworker has a high fever; I feel feverish; Someone is feeling unwell.
- **Proactive guidance:** Pause the affected work and seek the supervisor or first-aider for help. For cough or fever that may be a respiratory illness, avoid close contact with others and follow the site illness-reporting process. Record only the reported symptoms or observations; do not diagnose a cold, flu, or infection or prescribe medication.
- **Worker safety:** Trouble breathing or chest pain requires emergency medical help immediately. If coughing started near fumes, a spill, or an unusual smell, also use the matching exposure or unknown-odor procedure; do not assume infection explains it.
- **File with:** Floor supervisor or first-aid team, as a general-concern report. The person reporting may be a coworker; personal names are not needed for the synthetic demo.
- **Ask next:** From safety, ask whether there is trouble breathing or chest pain if not already known. Give emergency guidance immediately when those symptoms are reported. Collect a free-form location for the report only when safe; quantity and product identification are not required.
- **Get help:** Immediate emergency help takes priority over reporting for trouble breathing, chest pain, or collapse. Other severe or concerning symptoms need prompt medical advice; do not wait to finish the report.
- **Short demo response:** Pause work and get the supervisor or first-aider. Trouble breathing or chest pain needs emergency help now. Avoid close contact if this may be a respiratory illness.
- **Demo rule:** Synthetic workplace response informed by public health guidance; not a diagnosis or retailer-approved medical protocol. Contacts and resource locations require verified site records. Simulate emergency calls and notifications during demonstrations.
- **Source:** [Centers for Disease Control and Prevention respiratory-illness precautions](https://www.cdc.gov/respiratory-viruses/prevention/precautions-when-sick.html).

## If no SOP clearly matches

Keep the report in the worker's own words. If something may be unsafe, advise pausing the task and keeping clear. From a safe position, ask: "What did you notice?" Offer the supervisor or safety team without requiring a product match. Immediate danger or serious symptoms takes the emergency route.

The worker can always say "Get help", "I'm not sure", or "That's wrong". Guidance comes before forms; missing details do not block safety help. An offline report is saved or queued, not a confirmed escalation.

## Safety references

- [OSHA warehouse hazards](https://www.osha.gov/warehousing/hazards-solutions)
- [OSHA emergency action plans](https://www.osha.gov/etools/evacuation-plans-procedures/eap/)
- [OSHA safety data sheets](https://www.osha.gov/laws-regs/regulations/standardnumber/1910/1910.1200AppD)
- [OSHA heat illness and first aid](https://www.osha.gov/heat-exposure/illness-first-aid/)
- [PHMSA gas-leak response](https://www.phmsa.dot.gov/safety-awareness/pipeline/pipeline-leak-recognition-and-what-do)
- [CDC hand hygiene](https://www.cdc.gov/clean-hands/faq/)
- [Poison Control exposure first aid](https://www.poison.org/first-aid-for-poisonings)
- [Red Cross bleeding first aid](https://www.redcross.org/take-a-class/resources/learn-first-aid/bleeding-life-threatening-external)
- [National 911 Program calling guidance](https://www.911.gov/calling-911/frequently-asked-questions/)
- [OSHA evacuation guidance](https://www.osha.gov/emergency-preparedness/getting-started)
