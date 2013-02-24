  midi-clj
==============

#### A streamlined MIDI API for Clojure

midi-clj is being developed for Project Overtone, and it is meant to simplify
the usage of MIDI devices and the MIDI system from within Clojure.

    (use 'overtone.midi)

    ; Select MIDI input and output devices
    ; These functions bring up a GUI chooser window to select a MIDI port
    (def keyboard (midi-in))
    (def phat-synth (midi-out))

    ; Once you know the correct device names for your devices you can save the
    ; step of opening up the GUI chooser by putting a unique part of the name
    ; as an argument to midi-in or midi-out.  The first device with a name that
    ; matches with lookup is returned.
    (def ax (midi-in "axiom"))

    ; Connect ins and outs easily
    (midi-route keyboard phat-synth)

    ; Trigger a note (note 40, velocity 100)
    (midi-note-on phat-synth 40 100)
    (Thread/sleep 500)
    (midi-note-off phat-synth 0)

    ; Or the short-hand version to start and stop a note
    (midi-note phat-synth 40 100 500)

    ; And the same thing with a sequence of notes
    (midi-play phat-synth [40 47 40] [80 50 110] [250 500 250])

In Ubuntu Linux I use the snd-virmidi kernel module to provide software MIDI
ports.  USB MIDI devices should be pretty much plug and play.

#### Handling Events Yourself

The low-level midi-handle-events function connects an input to an output via
a filter function. An optional second function will be passed sysex events,
which are ignored otherwise.

    ; Connect an input to an output via a filter function
    (midi-handle-events my-keyboard my-filter-function)

    ; Same, with the optional sysex-handling function specified
    (midi-handle-events my-keyboard my-filter-function my-sysex-function)

The first function is passed a map that is a midi-msg map with an additional
:device entry. The midi-msg map has the keys

    (:channel :command :msg :note :velocity :data1 :data2
     :status :timestamp)

* :channel is zero-based.
* :command is a midi-shortmessage-command value such as :note-on or
  :control-change.
* :msg contains the ShortMessage Java object.
* :note and :data1 both contain the first data byte.
* :velocity and :data2 both contain the second data byte.
* :status contains a midi-shortmessage-command or midi-shortmessage-status
  value such as :note-on or :system-exclusive.
* :timeestamp contains the millisecond timestamp.

The :device value is the midi-in object that sent the message ("my-keyboard"
in the examples above).

#### Reading MIDI Files

To read a MIDI file, call overtone.midi.file/midi-file or
overtone.midilfile/midi-url. Both return map that contains the keys

    (:sequence :tracks :type :division-type :resolution :usecs :properties)

Examples:

    (use 'overtone.midi.file)
    
    ; Read a MIDI file by name
    (midi-file "/path/to/file.mid")
    
    ; Read a MIDI file by URL
    (midi-url "file:///path/to/file.mid")

### Project Info:

Include in your project.clj like so:

  [overtone/midi-clj "0.1"]

#### Source Repository
Downloads and the source repository can be found on GitHub:

  http://github.com/overtone/midi-clj

Eventually there will be more documentation for this library, but in the
meantime you can see it in use within the context of Project Overtone, located
here:

  http://github.com/overtone/overtone

#### Mailing List

For any questions, comments or patches, use the Overtone google group here:

http://groups.google.com/group/overtone

### Authors

* Jeff Rose
