(ns overtone.midi
  #^{:author "Jeff Rose"
     :doc "A higher-level API on top of the Java MIDI apis.  It makes it
           easier to configure midi input/output devices, route between devices,
           read/write control messages to devices, play notes, etc."}
  (:import
     (java.util.regex Pattern)
     (javax.sound.midi Sequencer Synthesizer
                       MidiSystem MidiDevice Receiver Transmitter MidiEvent
                       MidiMessage ShortMessage SysexMessage
                       InvalidMidiDataException MidiUnavailableException)
     (javax.swing JFrame JScrollPane JList
                  DefaultListModel ListSelectionModel)
     (java.awt.event MouseAdapter)
     (java.util.concurrent FutureTask ScheduledThreadPoolExecutor TimeUnit))
  (:use clojure.set))

(def NUM-PLAYER-THREADS 10)
(def midi-player-pool (ScheduledThreadPoolExecutor. NUM-PLAYER-THREADS))

(defn- now []
  (System/currentTimeMillis))

(defn- schedule
  "Schedules fun to be executed after ms-delay milliseconds."
  [fun ms-delay]
  (.schedule midi-player-pool fun (long ms-delay) TimeUnit/MILLISECONDS))

(defn midi-devices []
  "Get all of the currently available midi devices."
  (for [info (MidiSystem/getMidiDeviceInfo)]
    (let [device (MidiSystem/getMidiDevice info)]
      (with-meta
        {:name         (.getName info)
         :description  (.getDescription info)
         :vendor       (.getVendor info)
         :version      (.getVersion info)
         :sources      (.getMaxTransmitters device)
         :sinks        (.getMaxReceivers device)
         :info         info
         :device       device}
        {:type :midi-device}))))

(defn midi-device?
  "Check whether obj is a midi device."
  [obj]
  (= :midi-device (type obj)))

(defn midi-ports
  "Get the available midi I/O ports (hardware sound-card and virtual ports)."
  []
  (filter #(and (not (instance? Sequencer   (:device %1)))
                (not (instance? Synthesizer (:device %1))))
          (midi-devices)))

;; NOTE: devices use -1 to signify unlimited sources or sinks

(defn midi-sources []
  "Get the midi input sources."
  (filter #(not (zero? (:sources %1))) (midi-ports)))

(defn midi-sinks
  "Get the midi output sinks."
  []
  (filter #(not (zero? (:sinks %1))) (midi-ports)))

(defn midi-find-device
  "Takes a set of devices returned from either (midi-sources) or (midi-sinks), and a
  search string.  Returns the first device where either the name or description
  mathes using the search string as a regexp."
  [devs dev-name]
  (first (filter
           #(let [pat (Pattern/compile dev-name Pattern/CASE_INSENSITIVE)]
              (or (re-find pat (:name %1))
                  (re-find pat (:description %1))))
           devs)))

(defn- list-model
  "Create a swing list model based on a collection."
  [items]
  (let [model (DefaultListModel.)]
    (doseq [item items]
      (.addElement model item))
    model))

(defn midi-port-chooser
  "Brings up a GUI list of the provided midi ports and then calls handler with the port
  that was double clicked."
  [title ports]
  (let [frame   (JFrame. title)
        model   (list-model (for [port ports]
                              (str (:name port) " - " (:description port))))
        options (JList. model)
        pane    (JScrollPane. options)
        future-val (FutureTask. #(nth ports (.getSelectedIndex options)))
        listener (proxy [MouseAdapter] []
                   (mouseClicked
                     [event]
                     (if (= (.getClickCount event) 2)
                       (.setVisible frame false)
                       (.run future-val))))]
    (doto options
      (.addMouseListener listener)
      (.setSelectionMode ListSelectionModel/SINGLE_SELECTION))
    (doto frame
      (.add pane)
      (.pack)
      (.setSize 400 600)
      (.setVisible true))
    future-val))

(defn- with-receiver
  "Add a midi receiver to the sink device info."
  [sink-info]
  (let [dev (:device sink-info)]
    (if (not (.isOpen dev))
      (.open dev))
    (assoc sink-info :receiver (.getReceiver dev))))

(defn- with-transmitter
  "Add a midi transmitter to the source info."
  [source-info]
  (let [dev (:device source-info)]
    (if (not (.isOpen dev))
      (.open dev))
    (assoc source-info :transmitter (.getTransmitter dev))))

(defn midi-in
  "Open a midi input device for reading.  If no argument is given then
  a selection list pops up to let you browse and select the midi device."
  ([] (with-transmitter
        (.get (midi-port-chooser "Midi Input Selector" (midi-sources)))))
  ([in]
   (let [source (cond
                  (string? in) (midi-find-device (midi-sources) in)
                  (midi-device? in) in)]
     (if source
       (with-transmitter source)
       (do
         (println "Did not find a matching midi input device for: " in)
         nil)))))

(defn midi-out
  "Open a midi output device for writing.  If no argument is given then
  a selection list pops up to let you browse and select the midi device."
  ([] (with-receiver
        (.get (midi-port-chooser "Midi Output Selector" (midi-sinks)))))

  ([out] (let [sink (cond
                      (string? out) (midi-find-device (midi-sinks) out)
                      (midi-device? out) out)]
           (if sink
             (with-receiver sink)
             (do
               (println "Did not find a matching midi output device for: " out)
               nil)))))

(defn midi-route
  "Route midi messages from a source to a sink.  Expects transmitter and receiver objects
  returned from midi-in and midi-out."
  [source sink]
  (.setReceiver (:transmitter source) (:receiver sink)))

(def midi-shortmessage-status
  {ShortMessage/ACTIVE_SENSING :active-sensing
   ShortMessage/CONTINUE :continue
   ShortMessage/END_OF_EXCLUSIVE :end-of-exclusive
   ShortMessage/MIDI_TIME_CODE :midi-time-code
   ShortMessage/SONG_POSITION_POINTER :song-position-pointer
   ShortMessage/SONG_SELECT :song-select
   ShortMessage/START :start
   ShortMessage/STOP :stop
   ShortMessage/SYSTEM_RESET :system-reset
   ShortMessage/TIMING_CLOCK :timing-clock
   ShortMessage/TUNE_REQUEST :tune-request})

(def midi-sysexmessage-status
  {SysexMessage/SYSTEM_EXCLUSIVE :system-exclusive
   SysexMessage/SPECIAL_SYSTEM_EXCLUSIVE :special-system-exclusive})

(def midi-shortmessage-command
  {ShortMessage/CHANNEL_PRESSURE :channel-pressure
   ShortMessage/CONTROL_CHANGE :control-change
   ShortMessage/NOTE_OFF :note-off
   ShortMessage/NOTE_ON :note-on
   ShortMessage/PITCH_BEND :pitch-bend
   ShortMessage/POLY_PRESSURE :poly-pressure
   ShortMessage/PROGRAM_CHANGE :program-change})

(def midi-shortmessage-keys
  (merge midi-shortmessage-status midi-shortmessage-command))

;;
;; Note-off event:
;; MIDI may send both Note-On and Velocity 0 or Note-Off.
;;
;; http://www.jsresources.org/faq_midi.html#no_note_off
(defn midi-msg
  "Make a clojure map out of a midi object."
  [obj]
  {:chan (.getChannel obj)
   :cmd  (if (and (= ShortMessage/NOTE_ON (.getCommand obj))
                  (== 0 (.getData2 obj) 0))
           :note-off
           (midi-shortmessage-keys (.getCommand obj)))
   :note (.getData1 obj)
   :vel  (.getData2 obj)
   :data1 (.getData1 obj)
   :data2 (.getData2 obj)
   :status (midi-shortmessage-keys (.getStatus obj))})

(defn midi-handle-events
  "Specify a single handler that will receive all midi events from the input device."
  [input fun]
  (let [receiver (proxy [Receiver] []
                   (close [] nil)
                   (send [msg timestamp] (fun (midi-msg msg) timestamp)))]
    (.setReceiver (:transmitter input) receiver)))

;; NOTE: Unfortunately, it seems that either Pianoteq or the virmidi modules
;; don't actually make use of the timestamp...
(defn midi-note-on
  "Send a midi on msg to the sink."
  [sink note-num vel & [timestamp]]
  (let [on-msg  (ShortMessage.)]
    (.setMessage on-msg ShortMessage/NOTE_ON 0 note-num vel)
    (.send (:receiver sink) on-msg -1)))

(defn midi-note-off
  "Send a midi off msg to the sink."
  [sink note-num]
  (let [off-msg (ShortMessage.)]
    (.setMessage off-msg ShortMessage/NOTE_OFF 0 note-num 0)
    (.send (:receiver sink) off-msg -1)))

(defn- byte-seq-to-array
  "Turn a seq of bytes into a native byte-array."
  [bseq]
  (let [ary (byte-array (count bseq))]
    (doseq [i (range (count bseq))]
      (aset-byte ary i (nth bseq i)))
    ary))

(defn midi-sysex
  "Send a midi System Exclusive msg made up of the bytes in byte-seq to the sink."
  [sink byte-seq]
  (let [sys-msg (SysexMessage.)
        bytes (byte-seq-to-array byte-seq)]
    (.setMessage sys-msg bytes (count bytes))
    (.send (:receiver sink) sys-msg -1)))

(defn midi-note
  "Send a midi on/off msg pair to the sink."
  [sink note-num vel dur]
  (midi-note-on sink note-num vel)
  (schedule #(midi-note-off sink note-num 0) dur))

(defn midi-play
  "Play a seq of notes with the corresponding velocities and durations."
  [out notes velocities durations]
  (loop [notes notes
         velocities velocities
         durations durations
         cur-time  0]
    (if notes
      (let [n (first notes)
            v (first velocities)
            d (first durations)]
        (schedule #(midi-note out n v d) cur-time)
        (recur (next notes) (next velocities) (next durations) (+ cur-time d))))))
