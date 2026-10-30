import type { Unit } from '../types'

export const UNITS: Unit[] = ['LITER', 'ML', 'KG', 'GRAM', 'PIECE', 'DOZEN', 'PACKET', 'BOX', 'BOTTLE']

export const UNIT_OPTIONS = UNITS.map((unit) => ({ value: unit, label: unit }))

const FAMILIES: Record<Unit, Unit[]> = {
  LITER: ['LITER', 'ML'],
  ML: ['LITER', 'ML'],
  KG: ['KG', 'GRAM'],
  GRAM: ['KG', 'GRAM'],
  PIECE: ['PIECE', 'DOZEN'],
  DOZEN: ['PIECE', 'DOZEN'],
  PACKET: ['PACKET'],
  BOX: ['BOX'],
  BOTTLE: ['BOTTLE'],
}

export function unitsFor(base: Unit): Unit[] {
  return FAMILIES[base] ?? [base]
}

export function unitOptionsFor(base: Unit | undefined) {
  const units = base ? unitsFor(base) : UNITS
  return units.map((unit) => ({ value: unit, label: unit }))
}
