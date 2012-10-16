(ns overtone.midi
  #^{:author "Jeff Rose"
     :doc "A higher-level API on top of the Java MIDI apis.  It makes
           it easier to configure midi input/output devices, route
           between devices, read/write control messages to devices,
           play notes, etc."}
  (:import
     (java.util.regex Pattern)
     (javax.sound.midi Sequencer Synthesizer
                       MidiSystem MidiDevice Receiver Transmitter MidiEvent
                       MidiMessage ShortMessage SysexMessage
                       InvalidMidiDataException MidiUnavailableException
                       MidiDevice$Info)
     (javax.swing JFrame JScrollPane JList
                  DefaultListModel ListSelectionModel)
     (java.awt.event MouseAdapter)
     (java.util.concurrent FutureTask ScheduledThreadPoolExecutor TimeUnit))
  (:use clojure.set)
  (:require [overtone.at-at :as at-at]))

; Java MIDI returns -1 when a port can support any number of transmitters or
; receivers, we use max int.
(def MAX-IO-PORTS Integer/MAX_VALUE)

(def NUM-PLAYER-THREADS 10)

(def midi-player-pool (at-at/mk-pool))


(defn midi-devices []
  "Get all of the currently available midi devices."
  (for [^MidiDevice$Info info (MidiSystem/getMidiDeviceInfo)]
    (let [device (MidiSystem/getMidiDevice info)
          n-tx   (.getMaxTransmitters device)
          n-rx   (.getMaxReceivers device)]
      (with-meta
        {:name         (.getName info)
         :description  (.getDescription info)
         :vendor       (.getVendor info)
         :version      (.getVersion info)
         :sources      (if (neg? n-tx) MAX-IO-PORTS n-tx)
         :sinks        (if (neg? n-rx) MAX-IO-PORTS n-rx)
         :info         info
         :device       device}
        {:type :midi-device}))))

(defn midi-device?
  "Check whether obj is a midi device."
  [obj]
  (= :midi-device (type obj)))

(defn midi-ports
  "Get the available midi I/O ports (hardware sound-card and virtual
  ports). NOTE: devices use -1 to signify unlimited sources or sinks."
  []
  (filter #(and (not (instance? Sequencer   (:device %1)))
                (not (instance? Synthesizer (:device %1))))
          (midi-devices)))

(defn midi-sources []
  "Get the midi input sources."
  (filter #(not (zero? (:sources %1))) (midi-ports)))

(defn midi-sinks
  "Get the midi output sinks."
  []
  (filter #(not (zero? (:sinks %1))) (midi-ports)))

(defn midi-find-device
  "Takes a set of devices returned from either (midi-sources)
  or (midi-sinks), and a search string.  Returns the first device
  where either the name or description matches using the search string
  as a regexp."
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

(defn- midi-port-chooser
  "Brings up a GUI list of the provided midi ports and then calls
  handler with the port that was double clicked."
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
  (let [^MidiDevice dev (:device sink-info)]
    (if (not (.isOpen dev))
      (.open dev))
    (assoc sink-info :receiver (.getReceiver dev))))

(defn- with-transmitter
  "Add a midi transmitter to the source info."
  [source-info]
  (let [^MidiDevice dev (:device source-info)]
    (if (not (.isOpen dev))
      (.open dev))
    (assoc source-info :transmitter (.getTransmitter dev))))

(defn midi-in
  "Open a midi input device for reading.  If no argument is given then
  a selection list pops up to let you browse and select the midi
  device."
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
  "Open a midi output device for writing.  If no argument is given
  then a selection list pops up to let you browse and select the midi
  device."
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
  "Route midi messages from a source to a sink.  Expects transmitter
  and receiver objects returned from midi-in and midi-out."
  [source sink]
  (let [^Transmitter tran (:transmitter source)]
    (.setReceiver tran (:receiver sink))))

(def midi-shortmessage-status
  {ShortMessage/ACTIVE_SENSING         :active-sensing
   ShortMessage/CONTINUE               :continue
   ShortMessage/END_OF_EXCLUSIVE       :end-of-exclusive
   ShortMessage/MIDI_TIME_CODE         :midi-time-code
   ShortMessage/SONG_POSITION_POINTER  :song-position-pointer
   ShortMessage/SONG_SELECT            :song-select
   ShortMessage/START                  :start
   ShortMessage/STOP                   :stop
   ShortMessage/SYSTEM_RESET           :system-reset
   ShortMessage/TIMING_CLOCK           :timing-clock
   ShortMessage/TUNE_REQUEST           :tune-request})

(def midi-sysexmessage-status
  {SysexMessage/SYSTEM_EXCLUSIVE         :system-exclusive
   SysexMessage/SPECIAL_SYSTEM_EXCLUSIVE :special-system-exclusive})

(def midi-shortmessage-command
  {ShortMessage/CHANNEL_PRESSURE :channel-pressure
   ShortMessage/CONTROL_CHANGE   :control-change
   ShortMessage/NOTE_OFF         :note-off
   ShortMessage/NOTE_ON          :note-on
   ShortMessage/PITCH_BEND       :pitch-bend
   ShortMessage/POLY_PRESSURE    :poly-pressure
   ShortMessage/PROGRAM_CHANGE   :program-change})

(def midi-shortmessage-keys
  (merge midi-shortmessage-status midi-shortmessage-command))

;;
;; Note-off event:
;; MIDI may send both Note-On and Velocity 0 or Note-Off.
;;
;; http://www.jsresources.org/faq_midi.html#no_note_off
(defn midi-msg
  "Make a clojure map out of a midi ShortMessage object."
  [^ShortMessage obj & [ts]]
  (let [ch     (.getChannel obj)
        cmd    (.getCommand obj)
        d1     (.getData1 obj)
        d2     (.getData2 obj)
        status (.getStatus obj)]
  {:channel   ch
   :command   (if (and (= ShortMessage/NOTE_ON cmd)
                       (== 0 (.getData2 obj) 0))
                :note-off
                (midi-shortmessage-keys cmd))
   :msg       obj
   :note      d1
   :velocity  d2
   :data1     d1
   :data2     d2
   :status    (midi-shortmessage-keys status)
   :timestamp ts}))

(defn midi-handle-events
  "Specify a single handler that will receive all midi events from the input device.
  The handler should be a function of one argument, which is a midi event map."
  [input fun]
  (let [receiver (proxy [Receiver] []
                   (close [] nil)
                   (send [msg timestamp] (when (instance? ShortMessage msg )
                                           (fun
                                            (assoc (midi-msg msg timestamp)
                                              :device input)))))]
    (.setReceiver (:transmitter input) receiver)
    receiver))

(defn midi-send-msg
  [^Receiver sink msg val]
  (.send sink msg val))

(defn midi-note-on
  "Send a midi on msg to the sink."
  ([sink note-num vel]
     (midi-note-on sink note-num vel 0))
  ([sink note-num vel channel]
     (let [on-msg  (ShortMessage.)]
       (.setMessage on-msg ShortMessage/NOTE_ON channel note-num vel)
       (midi-send-msg (:receiver sink) on-msg -1))))

(defn midi-note-off
  "Send a midi off msg to the sink."
  ([sink note-num]
     (midi-note-off sink note-num 0))
  ([sink note-num channel]
     (let [off-msg (ShortMessage.)]
       (.setMessage off-msg ShortMessage/NOTE_OFF channel note-num 0)
       (midi-send-msg (:receiver sink) off-msg -1))))

(defn midi-control
  "Send a control msg to the sink"
  ([sink ctl-num val]
     (midi-control sink ctl-num val 0))
  ([sink ctl-num val channel]
     (let [ctl-msg (ShortMessage.)]
       (.setMessage ctl-msg ShortMessage/CONTROL_CHANGE channel ctl-num val)
       (midi-send-msg (:receiver sink) ctl-msg -1))))

(def hex-char-values (hash-map 
  \0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9 
  \a 10 \b 11 \c 12 \d 13 \e 14 \f 15 
  \A 10 \B 11 \C 12 \D 13 \E 14 \F 15 
  \space \space \, \space \newline \space 
  \tab \space \formfeed \space \return \space))

(defn- not-space? 
  [v] (not= \space v))

(defn- byte-str-to-seq
  "Turn a case-insensitive string of hex bytes into a seq.
  Bytes can optionally be delimited by commas or whitespace"
  [midi-str]  
  (map #(+ (* 16 (first %)) (second %))
    (partition-all 2 (map hex-char-values (filter not-space? (seq midi-str))))))
   
(defn- byte-seq-to-array 
  "Turn a seq of bytes into a native byte-array of 2s-complement values."
  [bseq]
  (let [ary (byte-array (count bseq))]
    (doseq [i (range (count bseq))]
      (aset-byte ary i (unchecked-byte(nth bseq i))))
    ary))

(defmulti midi-sysex
  "Send a midi System Exclusive msg made up of the bytes in byte-seq
   byte-array, or a byte-string to the sink.  The byte string must
   only contain bytes encoded as hex values.  Commas, spaces, and other
   whitespace is ignored"
   (fn[sink byte-seq] (type (first byte-seq))))
  
(defmethod midi-sysex java.lang.Character [sink byte-seq]
  (let [ sys-msg (SysexMessage.)
     bytes (byte-seq-to-array (byte-str-to-seq byte-seq))]
    (.setMessage sys-msg bytes (count bytes))
    (midi-send-msg (:receiver sink) sys-msg -1)))
    
(defmethod midi-sysex :default [sink byte-seq]
  [sink byte-seq]
  (let [sys-msg (SysexMessage.)
        bytes (if (= (type bytes) (type (byte-array 0)))
                bytes
                (byte-seq-to-array (seq byte-seq)))]
    (.setMessage sys-msg bytes (count bytes))
    (midi-send-msg (:receiver sink) sys-msg -1)))

(defn midi-note
  "Send a midi on/off msg pair to the sink."
  ([sink note-num vel dur]
     (midi-note sink note-num vel dur 0))
  ([sink note-num vel dur channel]
     (midi-note-on sink note-num vel channel)
     (at-at/after dur #(midi-note-off sink note-num channel) midi-player-pool)))

(defn midi-play
  "Play a seq of notes with the corresponding velocities and
  durations."
  ([out notes velocities durations]
     (midi-play out notes velocities durations 0))
  ([out notes velocities durations channel]
     (loop [notes notes
            velocities velocities
            durations durations
            cur-time  0]
       (if notes
         (let [n (first notes)
               v (first velocities)
               d (first durations)]
           (at-at/after cur-time #(midi-note out n v d channel) midi-player-pool)
           (recur (next notes) (next velocities) (next durations) (+ cur-time d)))))))
