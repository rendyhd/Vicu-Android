# The planner on phones

Give tasks a time on a timeline, and plan a day from what you have. It is off until you turn it on in
Settings, General, Plan (right after Pro), per device. The times you set show on every device either way, because they
are the task's own start and end dates.

## Time blocks

A block says when you mean to do a task. It sits on top of the due date: the due date says which day
a task belongs to and when it is due, the block says what time is set aside for it. A task without a
block is a complete task, and nothing moves on its own.

You can make one anywhere you type a task: `Call Sam tomorrow at 3pm for 30m`.

- A task with a block today is on Today, even when it is due later.
- A row shows the block ("15:00–15:30"). A date-only due date on the same day is left out, because
  the block already says that day.
- Change the due date and a block on that day moves with it. Clear the date and the time goes too.

## The Plan screen

One day at a time, with a strip of seven days above it.

- **Swipe the timeline** sideways for the next or previous day. Past the end of the strip, the strip
  moves a week. A swipe on the strip itself goes a week.
- **No time**, the row above the hours, holds the day's tasks without a time. Tap one and then a
  time to place it, press and hold it to drag it, or tick it.
- **The tab at the right edge** opens the tasks to plan. Its header names the list on show with its
  count ("Today 8"); tap it for the others: the selected day, the Inbox, Next 7 days, No date or a
  project. The search button filters the list by name. Tap a task and then a time to place it, or press
  and hold it and drag it onto the timeline. It takes the default length (the gear; 30 minutes to
  start, synced to your other devices). A due date before that time moves to that day, and the
  message says so, with Undo; a later one stays as a deadline.
- **Press and hold a block** to move it, **drag its handle** to change the length, both in 15-minute
  steps. **Tap a block** for another day, exact lengths, Remove time and, on a very short block, its
  check.
- **Another day while you drag.** Rest the block or task on a day of the strip for half a second, or
  tap the day with a second finger, and that day opens under your finger. Drop it at a time there, or
  let go on the strip day to keep the same time on that day. Let go on the No time row to remove the
  time and keep the day.

Routines show as small pills at their times. They have no length and never count as busy. Turn them
off in the gear.

Today has a List and a Day switch while the planner is on. Day shows the timeline and opens and ticks
tasks; changing them happens on Plan.

## Events and calendars

Plan also shows what takes your time that is not a task. Events and calendars are on Plan and in Plan
my day only, never on Today, in lists or in reminders.

- **Your events.** Press and hold an empty time and choose Event: a weekly manager call, a monthly
  review, a month-end close. Pick how it repeats (the choices fit the day you pressed). Events have
  nothing to tick, sync to your other devices through your Vikunja server, and count as busy time
  unless you turn that off. The gear lists them and has New event.
- **Import a calendar file.** The gear's Import calendar file opens an `.ics` file (a school timetable,
  public holidays, an exported work schedule) and shows what it becomes: how many repeating and single
  events, and each one. A series whose repeat Vicu has (daily, weekly, monthly on a date or a weekday,
  yearly, with its end and skipped days) becomes one repeating event; anything else becomes single
  events for the next 12 months. Times with a time zone become your local time. "Busy only" makes
  every title Busy, or Free for a free event. Import adds them all at once, with Undo; afterwards they
  are your own events, synced and changeable, and nothing stays linked to the file.
- **Change an Event** by tapping it: title, all day, time and length, the day, how it repeats, when it
  ends and whether it counts as busy, then Save. Press and hold it to move it, or drag its handle to
  change its length, like a block. A repeating Event asks whether the change is for this time only,
  this and following, or all times. Skip this one and Delete are there too; each has Undo.
- **The phone's calendars.** The gear's Calendars asks to read your calendars and lists the ones the
  phone has, grouped by account: on Android what it syncs (Google, Exchange through Outlook, iCloud or
  other CalDAV through DAVx5, subscribed links through ICSx5), on an iPhone the accounts in Settings,
  Calendar (iCloud, Google, Exchange, CalDAV, subscriptions; full access, asked once). Each can be shown
  or hidden, take its own colour or one you pick, count as busy time or not, and show only Busy instead
  of titles. Vicu never changes these calendars and never asks to write to them, and nothing about them
  leaves the phone; refusing access leaves your own Events working. Pulling down on the timeline reads
  them again at once, with your tasks.
- **Busy time.** Timed, busy events of calendars that count are busy for free time, Next free, the
  load bars under the strip's days and Plan my day; all-day and free events are not. The assistant only gets your free time, never an
  event's title.

## Plan my day

The sun button on Plan. Four steps, today or tomorrow. It works fully without the assistant.

1. **Leftovers.** Unfinished tasks from before: Today, Tomorrow, No date or Done, one at a time, or skip
   them. Each is written at once.
2. **Choose.** Tick what you will do, from the day, the Inbox, what is coming up or a project. A meter
   compares it with your free time, which is your work hours (the gear; 08:00 to 18:00 to start) less
   what is planned. Tap a length to change it.
3. **Arrange.** The dashed blocks are a draft in your free slots, and nothing is saved yet. Tap one to
   move it earlier or later, change its length or keep it without a time, or press and hold it and drag
   it. Accept the plan writes it.
4. **Ready.** A summary of the day. Undo all puts back everything the session changed. Remind me when
   each block starts writes a reminder on each block. Ask me to plan tomorrow sends one notification
   each evening at the time you set, on this device; you can also set it in Settings, General, Plan or
   in the gear. Tapping the notification opens Plan.

## With the assistant

With Ask set up (Settings, Assistant), a switch in the session adds suggestions, each dashed with a
short reason. Nothing is saved until you tap.

- **Leftovers:** one of the four for each, with Apply the suggestions.
- **Choose:** how long each task takes, tasks worth adding, and what can wait when you are over.
- **Arrange:** an order with start times, long work in long gaps and quick tasks in short ones. First
  free slots is one tap away.

It sends one request when the session opens, on Ask's model, and another when you change the day or
press Ask again. It reads the day from the server first, so the free time it sends is current. It sends
each task's title, project, priority, due date, subtask count and the start of its notes, your free
time and your routines' times, without their names. If the request fails it says so once and the
session carries on with first free slots and your default length.

The contract is `docs/cross-app-semantics-v1.md` sections 14.10 and 16, and
`docs/ai-assistant-v1.md` section 10. Developer notes are in `CLAUDE.md`, Day planner.
