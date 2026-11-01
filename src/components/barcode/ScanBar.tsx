import { Camera, ScanBarcode } from 'lucide-react'
import { useState } from 'react'
import { Button, Input } from '../ui'
import { BarcodeScanDialog } from './BarcodeScanDialog'

interface ScanBarProps {
  onDetected: (value: string) => boolean | Promise<boolean>
  placeholder?: string
  disabled?: boolean
  className?: string
}

/**
 * Desktop scanners act as keyboards, so a focused input that reacts to Enter is
 * all that is needed. The camera dialog covers phones and tablets.
 */
export function ScanBar({
  onDetected,
  placeholder = 'Scan barcode, then press Enter',
  disabled = false,
  className = '',
}: ScanBarProps) {
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [cameraOpen, setCameraOpen] = useState(false)

  const handle = async (raw: string) => {
    const code = raw.trim()
    if (!code || busy || disabled) return false
    setBusy(true)
    try {
      const accepted = await onDetected(code)
      if (accepted) setValue('')
      return accepted
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`flex items-center gap-2 ${className}`}>
      <div className="relative min-w-0 flex-1">
        <ScanBarcode className="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-slate-400" />
        <Input
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              void handle(value)
            }
          }}
          placeholder={placeholder}
          className="pl-9 font-mono text-xs"
          disabled={disabled || busy}
          aria-label="Scan barcode"
          autoComplete="off"
          spellCheck={false}
        />
      </div>
      <Button
        variant="outline"
        size="sm"
        icon={<Camera className="h-3.5 w-3.5" />}
        onClick={() => setCameraOpen(true)}
        disabled={disabled || busy}
      >
        Camera
      </Button>
      <BarcodeScanDialog
        open={cameraOpen}
        onClose={() => setCameraOpen(false)}
        onDetected={async (code) => {
          const accepted = await handle(code)
          if (accepted) setCameraOpen(false)
          return accepted
        }}
      />
    </div>
  )
}
