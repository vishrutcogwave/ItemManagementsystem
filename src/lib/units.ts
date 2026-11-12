import type { Unit, UnitMaster } from '../types'

// Legacy unit families still convert between each other on the server. Units
// created in the Unit Master are their own family and only convert to themselves.
const LEGACY_FAMILIES: Record<string, string[]> = {
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

export interface UnitOption {
  value: Unit
  label: string
}

export function unitsFor(base: Unit): string[] {
  return LEGACY_FAMILIES[base.toUpperCase()] ?? [base]
}

export function unitOptionsFor(units: UnitMaster[], base?: Unit): UnitOption[] {
  const options = units.map((unit) => ({
    value: unit.unitCode,
    label: `${unit.unitCode} — ${unit.unitName}`,
  }))
  if (!base) {
    return options
  }
  const allowed = new Set(unitsFor(base))
  const matching = options.filter((option) => allowed.has(option.value.toUpperCase()))
  return matching.some((option) => option.value.toUpperCase() === base.toUpperCase())
    ? matching
    : [{ value: base, label: base }, ...matching]
}
