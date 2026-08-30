# GroupTrack — Production Access Application

**Where:** Play Console → Test and release → Production → *Apply for production access*
(both checks must show green: 12+ testers, 14 continuous days)

> ⚠ **Fill in the bracketed items before submitting.** Everything else is drawn from
> what GroupTrack actually did, not from a template. Google's reviewers are
> explicitly looking for specifics — named bugs, real changes, evidence the testing
> was genuine. Generic answers are the most common cause of rejection even when the
> 14 days are clean.

---

## 1) How did you recruit users for your closed test?

> Both, and the mix was deliberate. I recruited from the off-road riding community
> directly — riders who run side-by-sides, UTVs, ATVs, dirt bikes and Jeeps, which is
> exactly who GroupTrack is built for — and supplemented with a paid testing service
> to reach the tester count and get coverage on devices I do not own.
>
> Around **30 testers came from the riding community, recruited from March 2026
> onward**, and **15 came from a paid testing service**.
>
> The riders mattered more than the numbers. GroupTrack is used off-grid, on trails,
> often with no cell service, and the faults that matter only show up when someone is
> actually out there with it. Six months of riders using the app on real ground is
> where nearly all of the substantive feedback came from; the paid testers gave
> coverage on devices I do not own and kept the opt-in count stable.

---

## 2) How easy was it to recruit testers for your app?

**Easy** — *[or Moderate, your call]*

---

## 3) Describe the engagement you received from testers during your closed test

> Engagement was hands-on and specific. Testers used the app on real rides rather than
> opening it to satisfy a counter, and the reports reflected that — problems that only
> appear in the field, on real terrain, with real trail data loaded for their state.
>
> The most valuable report I received was also the least comfortable. A tester who
> rides regularly and uses Polaris Ride Command, Avenza, Gaia and onX wrote to say he
> had opened GroupTrack three times and could not work out how to use it — where those
> other apps had been intuitive enough that he never needed a manual. He described
> himself as closer to my average user than a twenty-something, and told me plainly
> that simplicity had to come before anything else.
>
> He then set out what he expected instead: open to a map of where he is, move and
> resize it, and download that area for offline use in one action, with the map type
> and level of detail as options. Then record or build a route. Then put himself on
> the map under a name and invite others to follow. And a short description on every
> menu item explaining what it does.
>
> That single message reshaped the release. It is the reason navigation moved from
> icons to text, and the reason route planning was rebuilt into one plainly worded
> step at a time rather than a checklist.
>
> Other reports were narrower but equally actionable — a rider finding the route
> planner would not use a dirt road he rides every week, and testers reporting that
> searching the same area twice produced worse results the second time. Both turned
> out to be real defects and both are fixed.

> ⚠ *Do not include his phone number or full contact details in the submission —
> the substance is what matters, and a first name or "a tester" is enough.*

---

## 4) Provide a summary of the feedback you received. Include how you collected it.

> Feedback came through direct communication with testers — email, messages and phone
> calls — and from crash reporting in the app. Reports have arrived steadily since the
> riding community joined in March 2026, not only during the closed-test window. Where
> a tester was stuck configuring the mesh radios their group rides with, I worked
> through it with them by phone.
>
> One thing that surfaced repeatedly: **the documentation lagged the app.** Setup steps
> that had changed between builds were not written down, and testers hit them. That is
> why the release now bundles a QuickStart shown on first install and release notes
> shown after an update, so a rider is not relying on me being reachable.
>
> The themes were:
>
> - **Route planning was too complicated.** The original flow presented a checklist of
>   every step at once. Testers found it dense and hard to follow on a phone.
> - **The planner would not use ground riders knew was rideable.** Dirt roads they ride
>   regularly were not available to the route builder.
> - **Repeat searches in the same area returned worse results than the first search.**
>   Reported as inconsistency, and it turned out to be a real defect.
> - **Testers could not reliably tell what the navigation icons meant.** This came up
>   often enough to be a pattern rather than a preference, and it fits the audience:
>   the average GroupTrack rider is older than the typical app user.
> - **The app allowed two map downloads to run at once**, which its own documentation
>   said was not permitted. A tester read the manual closely enough to spot the
>   discrepancy, then found two separate routes into it — from the menu and from the
>   drop-down.
> - **Downloaded maps survived an uninstall**, with no way to remove them from inside
>   the app — a tester had to go into the file system by hand. He asked for a menu item
>   to delete what had been downloaded.
> - **The app crawled on one tester's budget device.** Testing on hardware I do not own
>   is exactly what I could not do alone.
> - *[Any others — imports, map display, radio pairing, crashes.]*

---

## 5) Who is the intended audience for your app?

> Off-road riders — side-by-side, UTV, ATV, dirt bike and Jeep — who ride trails
> beyond cell coverage, usually in groups.
>
> The core problem GroupTrack solves is that these riders lose contact with each other
> and with their maps exactly where it matters most. The app works entirely offline:
> maps, trail data and navigation are on the device, and group position sharing runs
> over mesh radio rather than a cell network.

---

## 6) Describe how your app provides value to the users.

> GroupTrack does three things a rider cannot otherwise do off-grid:
>
> **Navigate without service.** Map tiles and trail data are downloaded before the ride
> and work with no connection.
>
> **Keep a group together.** Riders see each other's positions over mesh radio, so a
> convoy can spread out on a trail without losing anyone — the safety problem that
> motivated the app.
>
> **Plan a ride worth taking.** The planner reads the trail network on the device and
> builds complete rides to a rider's distance and pace, then shows several to compare.
> Doing this by hand on a paper map or a generic mapping app takes hours.

---

## 7) How many installs do you expect in your first year?

**1k – 10k**

> *[If the form allows any comment, or if it fits in Q9:]*
>
> The intention is deliberate, controlled growth toward around 1,000 riders in the
> first year, not volume. That number is what I can support properly while the training
> material and support tooling mature.
>
> The reason production access matters is distribution friction rather than scale. Today
> every tester has to be vetted and pre-registered by email before they can install,
> which is awkward for them and does not scale for me. A Play Store listing lets a rider
> find and install the app themselves.

---

## 8) What changes did you make based on what you learned during your closed test?

> ⭐ **This is the question that carries the application. Be concrete.**
>
> **Rebuilt the route planning flow.** Testers found the original checklist dense and
> hard to work through on a phone. It is now one prompt at a time, sitting at the
> bottom of the screen so the map stays usable, with a plain-language explanation of
> each step and the ability to start over at any point.
>
> **Fixed a defect that made repeat searches unreliable.** Testers reported that
> searching the same area twice gave worse results the second time. The cause was that
> the road network built for the first search was being reused in a modified state.
> Fixed, and verified by reproducing the original failure.
>
> **Added the ability to rebuild a ride from its saved settings.** Riders wanted the
> alternatives they had not kept. Every route now records what produced it, and one
> button regenerates from those settings.
>
> **Made shared routes carry their description.** A route sent to another rider now
> arrives with its written summary and the settings behind it, so the recipient can
> build their own variations.
>
> **Built proper download queues.** A tester found he could start a second map
> download while one was already running, which the documentation said was not allowed.
> The finding showed the restriction was the wrong answer rather than the behaviour
> being wrong — riders download several areas before a trip. Multiple queues are now
> supported and tuned, and the tester confirmed the fix.
>
> **Fixed application-not-responding errors on first launch.** Testers reported ANRs
> after installing a new build. I traced it, shipped a release specifically for it
> (v2.4, 14 May 2026), and confirmed with the affected testers that it was resolved.
>
> **Excluded a device class that cannot run the app.** A tester reported severe
> slowness. Investigating it on his device showed the cause: the handset advertises
> "4GB + 16GB" memory, but only about 2.9GB is real RAM visible to the system and the
> rest is swap on the flash storage chip, which is orders of magnitude slower. Under a
> full tile cache the app spills into swap and the device crawls. **I added that device
> class to the incompatible list in Play Console** rather than ship an experience that
> would look like a broken app to anyone who bought one.
>
> **Added map deletion, and let riders choose what goes.** A tester found that
> downloaded maps survived an uninstall and had to be removed through the file system.
> The first version I proposed deleted everything at once — testers pushed back, saying
> they wanted to choose which areas to remove. It now works the same way installing
> does: draw the area, delete it. **The design came from the testers, not from me.**
>
> **Replaced icon-based navigation with text.** Testers could not reliably identify
> what the icons meant. The average rider using GroupTrack is older than the typical
> app user, and they are reading the screen on a trail — often in bright sun, often
> wearing gloves. Labelled buttons removed the guesswork, and this is visible in the
> current screenshots.
>
> **Clearer guidance where testers got stuck** — telling riders to include the state
> when searching for an area, because the same place name exists in many states and
> landing in the wrong one is not obvious.
>
> *[Add anything else — crash fixes, import fixes, map display.]*

---

## 9) How did you decide your app is ready for production?

> The features a rider needs on a trail are complete and have been used on real rides:
> offline maps, trail data by state, group position sharing over mesh radio, and route
> planning. The faults testers found have been fixed and the fixes verified on device,
> not just in code.
>
> It is also stable in the way that matters for this app — it runs for hours with the
> screen on, recording a track, with no network. That is the condition it is used in.
>
> The stability problems testers found were addressed directly rather than deferred:
> the application-not-responding errors reported in May were traced and fixed in a
> release issued for that purpose, and where a device class genuinely could not run the
> app I excluded it rather than let a rider discover it on a trail.
>
> I am also not asking for production access in order to scale quickly. The plan is
> controlled growth to roughly 1,000 riders over the first year, so that quality,
> training material and support keep pace. Remote support has been a large part of how
> problems were diagnosed during testing, and that only works at a size I can actually
> service.
>
> *[If you have crash-free numbers from Play Console, quote them here — reviewers
> respond well to a figure.]*

---

## 10) What did you do differently this time?

> This is my first application for production access. What I would point to is what
> came before it.
>
> Before creating a closed test on Play at all, I ran **six months of testing with
> around 30 experienced side-by-side riders**, distributing builds directly. These are
> not casual testers: several have working knowledge of search-and-rescue applications,
> Meshtastic radio deployment, and off-road navigation requirements, and all of them use
> the established commercial apps in this category — Polaris Ride Command, Avenza, Gaia
> and onX — so their comparisons are informed.
>
> They have been engaged through the design and implementation of the software rather
> than handed a finished build to try. The changes listed above came out of that: the
> navigation was rewritten, map deletion was designed the way they asked for rather
> than the way I proposed, a device class was excluded, and the entire route planning
> flow was rebuilt after a tester told me plainly he had opened the app three times and
> could not use it.
>
> The Play closed test was the formal step at the end of that process, not the start
> of it.

---

## ⚠ Before you submit

- **Check both green lights** on the production access page. Applying early is
  rejected outright and you resubmit after the full period.
- **The 14 days must be continuous** and testers must have stayed opted in.
- **Review usually takes 7 days or less.**
- ⚠ **Do not paste the vendor's template.** Several of its answers describe an app
  that is not GroupTrack — a "Rate Your App" button and ASO optimisation were never
  your closed-test findings, and a reviewer comparing your answers to your actual
  release history will see that.
