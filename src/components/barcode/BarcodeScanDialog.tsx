import { Camera, CameraOff, ScanBarcode } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Input, Modal } from '../ui'

interface DetectedBarcode {
  rawValue: string
}

interface BarcodeDetectorLike {
  detect: (source: CanvasImageSource) => Promise<DetectedBarcode[]>
}

type BarcodeDetectorConstructor = new (options?: { formats?: string[] }) => BarcodeDetectorLike

interface BarcodeScanDialogProps {
  open: boolean
  onClose: () => void
  /** Return true when the scanned value was accepted, which closes the dialog. */
  onDetected: (value: string) => boolean | Promise<boolean>
}

const DETECTOR_FORMATS = ['ean_13', 'ean_8', 'code_128', 'code_39', 'upc_a', 'upc_e', 'qr_code']

function detectorConstructor(): BarcodeDetectorConstructor | null {
  return (
    (window as unknown as { BarcodeDetector?: BarcodeDetectorConstructor }).BarcodeDetector ?? null
  )
}

export function BarcodeScanDialog({ open, onClose, onDetected }: BarcodeScanDialogProps) {
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [cameraOn, setCameraOn] = useState(false)
  const [cameraError, setCameraError] = useState<string | null>(null)
  const videoRef = useRef<HTMLVideoElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const timerRef = useRef<number | null>(null)
  const busyRef = useRef(false)
  const submitRef = useRef<(raw: string) => void>(() => undefined)

  const stopCamera = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current)
      timerRef.current = null
    }
    streamRef.current?.getTracks().forEach((track) => track.stop())
    streamRef.current = null
    if (videoRef.current) videoRef.current.srcObject = null
    setCameraOn(false)
  }, [])

  useEffect(() => stopCamera, [stopCamera])

  const handleClose = useCallback(() => {
    stopCamera()
    setValue('')
    setCameraError(null)
    setBusy(false)
    onClose()
  }, [onClose, stopCamera])

  const submit = useCallback(
    async (raw: string) => {
      const code = raw.trim()
      if (!code || busyRef.current) return
      busyRef.current = true
      setBusy(true)
      setValue(code)
      try {
        const handled = await onDetected(code)
        if (handled) {
          handleClose()
        }
      } finally {
        busyRef.current = false
        setBusy(false)
      }
    },
    [handleClose, onDetected],
  )

  useEffect(() => {
    submitRef.current = (raw) => {
      void submit(raw)
    }
  }, [submit])

  const startCamera = async () => {
    setCameraError(null)
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError('This browser cannot access the camera. Check the browser version or use a USB scanner.')
      return
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' } },
        audio: false,
      })
      streamRef.current = stream
      setCameraOn(true)
    } catch (error) {
      const name = error instanceof DOMException ? error.name : ''
      setCameraError(
        name === 'NotAllowedError' || name === 'SecurityError'
          ? 'Camera permission was denied. Allow camera access for this site and try again.'
          : name === 'NotFoundError'
            ? 'No camera was found on this device.'
            : 'The camera could not be started. Close other apps using it and try again.',
      )
    }
  }

  useEffect(() => {
    if (!cameraOn) return
    const video = videoRef.current
    const stream = streamRef.current
    if (!video || !stream) return

    video.srcObject = stream
    void video.play().catch(() => undefined)

    let cancelled = false
    let zxingControls: { stop: () => void } | null = null
    const Detector = detectorConstructor()

    if (Detector) {
      const detector = new Detector({ formats: DETECTOR_FORMATS })
      const tick = async () => {
        if (cancelled || video.readyState < 2) return
        try {
          const results = await detector.detect(video)
          const match = results.find((result) => result.rawValue?.trim())
          if (match && !cancelled) submitRef.current(match.rawValue)
        } catch {
          // A dropped frame should not stop the scanning loop.
        }
      }

      timerRef.current = window.setInterval(() => {
        void tick()
      }, 350)
    } else {
      // Browsers without BarcodeDetector (Firefox, Safari, older Chromium) fall back to ZXing.
      void (async () => {
        try {
          const { BrowserMultiFormatReader } = await import('@zxing/browser')
          if (cancelled) return
          const reader = new BrowserMultiFormatReader()
          const controls = await reader.decodeFromStream(stream, video, (result) => {
            const code = result?.getText()?.trim()
            if (code && !cancelled) submitRef.current(code)
          })
          if (cancelled) {
            controls.stop()
          } else {
            zxingControls = controls
          }
        } catch {
          if (!cancelled) {
            setCameraError(
              'The camera stream could not be read. Close other apps using the camera and try again, or use a USB scanner.',
            )
          }
        }
      })()
    }

    return () => {
      cancelled = true
      zxingControls?.stop()
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current)
        timerRef.current = null
      }
    }
  }, [cameraOn])

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="Scan barcode"
      description="Scan an item barcode to add it to the current document."
      size="sm"
      footer={
        <Button variant="outline" onClick={handleClose}>
          Close
        </Button>
      }
    >
      <div className="space-y-4">
        <div className="rounded-xl border border-slate-200 bg-slate-50 p-3">
          <p className="text-[11px] leading-relaxed text-slate-500">
            USB and Bluetooth barcode scanners work as keyboards. Keep this field focused and scan
            the label — the item is detected as soon as the scanner sends Enter.
          </p>
          <div className="relative mt-2">
            <ScanBarcode className="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <Input
              autoFocus
              value={value}
              onChange={(event) => setValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  void submit(value)
                }
              }}
              placeholder="Scan or type a barcode, then press Enter"
              className="pl-9 font-mono"
              aria-label="Barcode value"
              inputMode="text"
              autoComplete="off"
              spellCheck={false}
            />
          </div>
          <div className="mt-2 flex items-center justify-between gap-2">
            <p className="text-[10px] text-slate-400">
              {busy ? 'Looking up the scanned item…' : 'Waiting for a scan'}
            </p>
            <Button size="sm" onClick={() => void submit(value)} disabled={!value.trim() || busy}>
              Use barcode
            </Button>
          </div>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between gap-2">
            <div>
              <p className="text-xs font-semibold text-slate-700">Camera scanning</p>
              <p className="text-[10px] text-slate-400">
                Rear camera on phone, tablet or laptop. Falls back to an on-device decoder where the browser has no
                native barcode support.
              </p>
            </div>
            <Button
              size="sm"
              variant="outline"
              icon={cameraOn ? <CameraOff className="h-3.5 w-3.5" /> : <Camera className="h-3.5 w-3.5" />}
              onClick={() => {
                if (cameraOn) {
                  stopCamera()
                } else {
                  void startCamera()
                }
              }}
            >
              {cameraOn ? 'Stop' : 'Start camera'}
            </Button>
          </div>

          {cameraOn && (
            <video
              ref={videoRef}
              muted
              playsInline
              className="aspect-video w-full rounded-lg border border-slate-200 bg-slate-900 object-cover"
            />
          )}

          {cameraError && (
            <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[11px] leading-relaxed font-medium text-amber-800">
              {cameraError}
            </p>
          )}
        </div>
      </div>
    </Modal>
  )
}
