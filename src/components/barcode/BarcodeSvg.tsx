import { useEffect, useRef } from 'react'
import { renderBarcodeIntoSvg } from '../../lib/barcode'
import type { BarcodeFormat } from '../../types'

interface BarcodeSvgProps {
  value: string
  format: BarcodeFormat
  height?: number
  width?: number
  displayValue?: boolean
  fontSize?: number
  className?: string
  title?: string
}

export function BarcodeSvg({
  value,
  format,
  height = 48,
  width = 1.6,
  displayValue = true,
  fontSize = 12,
  className = '',
  title,
}: BarcodeSvgProps) {
  const ref = useRef<SVGSVGElement>(null)

  useEffect(() => {
    if (!ref.current) return
    const ok = renderBarcodeIntoSvg(ref.current, value, format, {
      height,
      width,
      displayValue,
      fontSize,
      margin: 0,
    })
    if (!ok) {
      ref.current.replaceChildren()
      ref.current.setAttribute('data-invalid', 'true')
    } else {
      ref.current.removeAttribute('data-invalid')
    }
  }, [value, format, height, width, displayValue, fontSize])

  return (
    <svg
      ref={ref}
      role="img"
      aria-label={title ?? `Barcode ${value}`}
      className={className}
      style={{ maxWidth: '100%' }}
    />
  )
}
