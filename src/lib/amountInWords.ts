const ONES = [
  '',
  'One',
  'Two',
  'Three',
  'Four',
  'Five',
  'Six',
  'Seven',
  'Eight',
  'Nine',
  'Ten',
  'Eleven',
  'Twelve',
  'Thirteen',
  'Fourteen',
  'Fifteen',
  'Sixteen',
  'Seventeen',
  'Eighteen',
  'Nineteen',
]

const TENS = ['', '', 'Twenty', 'Thirty', 'Forty', 'Fifty', 'Sixty', 'Seventy', 'Eighty', 'Ninety']

function belowHundred(value: number): string {
  if (value < 20) return ONES[value]
  const tens = Math.floor(value / 10)
  const ones = value % 10
  return ones === 0 ? TENS[tens] : `${TENS[tens]} ${ONES[ones]}`
}

function belowThousand(value: number): string {
  const hundreds = Math.floor(value / 100)
  const rest = value % 100
  const parts: string[] = []
  if (hundreds > 0) parts.push(`${ONES[hundreds]} Hundred`)
  if (rest > 0) parts.push(belowHundred(rest))
  return parts.join(' ')
}

function indianWords(value: number): string {
  if (value === 0) return 'Zero'
  const parts: string[] = []
  let remaining = value
  const crore = Math.floor(remaining / 10000000)
  remaining %= 10000000
  const lakh = Math.floor(remaining / 100000)
  remaining %= 100000
  const thousand = Math.floor(remaining / 1000)
  remaining %= 1000
  if (crore > 0) parts.push(`${indianWords(crore)} Crore`)
  if (lakh > 0) parts.push(`${belowThousand(lakh)} Lakh`)
  if (thousand > 0) parts.push(`${belowThousand(thousand)} Thousand`)
  if (remaining > 0) parts.push(belowThousand(remaining))
  return parts.join(' ')
}

/** Indian-numbering amount in words, used on the A4 tax invoice footer. */
export function amountInWords(value: number): string {
  const totalPaise = Math.abs(Math.round(Number(value || 0) * 100))
  const rupees = Math.floor(totalPaise / 100)
  const paise = totalPaise % 100
  if (rupees === 0 && paise === 0) return 'Rupees Zero Only'
  const rupeeWords = rupees > 0 ? `Rupees ${indianWords(rupees)}` : ''
  const paiseWords = paise > 0 ? `and Paise ${belowHundred(paise)}` : ''
  return `${[rupeeWords, paiseWords].filter(Boolean).join(' ')} Only`
}
