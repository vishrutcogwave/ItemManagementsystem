import { useEffect, useRef } from 'react'
import type { PointerEvent as ReactPointerEvent, ReactNode } from 'react'

interface ScrollRowProps {
  children: ReactNode
  className?: string
  label?: string
}

/**
 * Horizontal strip that stays reachable with every input device: native
 * trackpad/touch scrolling, shift+wheel, plain wheel over the strip, and
 * click-drag with a mouse. A drag never triggers the button underneath.
 */
export function ScrollRow({ children, className = '', label }: ScrollRowProps) {
  const ref = useRef<HTMLDivElement>(null)
  const drag = useRef({ active: false, startX: 0, startScroll: 0, moved: false, pointerId: -1 })
  const suppressClick = useRef(false)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const onWheel = (event: WheelEvent) => {
      if (event.shiftKey || event.ctrlKey) return
      if (Math.abs(event.deltaX) >= Math.abs(event.deltaY)) return
      const max = el.scrollWidth - el.clientWidth
      if (max <= 0) return
      const next = Math.min(max, Math.max(0, el.scrollLeft + event.deltaY))
      if (next === el.scrollLeft) return
      event.preventDefault()
      el.scrollLeft = next
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    suppressClick.current = false
    if (event.pointerType !== 'mouse' || event.button !== 0) return
    const el = ref.current
    if (!el || el.scrollWidth <= el.clientWidth) return
    drag.current = {
      active: true,
      startX: event.clientX,
      startScroll: el.scrollLeft,
      moved: false,
      pointerId: event.pointerId,
    }
  }

  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const state = drag.current
    const el = ref.current
    if (!state.active || !el || event.pointerId !== state.pointerId) return
    const delta = event.clientX - state.startX
    if (!state.moved && Math.abs(delta) < 4) return
    if (!state.moved) {
      state.moved = true
      el.setPointerCapture(event.pointerId)
      el.classList.add('cursor-grabbing')
    }
    el.scrollLeft = state.startScroll - delta
  }

  const endDrag = () => {
    const el = ref.current
    drag.current.active = false
    if (drag.current.moved) suppressClick.current = true
    if (el) el.classList.remove('cursor-grabbing')
  }

  return (
    <div
      ref={ref}
      role="group"
      aria-label={label}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onClickCapture={(event) => {
        if (!suppressClick.current) return
        suppressClick.current = false
        event.preventDefault()
        event.stopPropagation()
      }}
      className={`thin-scrollbar flex cursor-grab overflow-x-auto overscroll-x-contain select-none ${className}`}
    >
      {children}
    </div>
  )
}
