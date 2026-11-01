export const PASSWORD_MIN = 8
export const PASSWORD_MAX = 72
export const PASSWORD_HINT = `${PASSWORD_MIN}-${PASSWORD_MAX} characters with upper-case, lower-case, number and special character`

const COMMON_PASSWORDS = new Set([
  'password',
  'password1',
  'password123',
  '12345678',
  '123456789',
  'qwerty123',
  'admin123',
  'letmein',
  'welcome1',
  'iloveyou',
  'abc12345',
  'changeme',
  'passw0rd',
  'administrator',
])

/** Mirrors the server-side PasswordPolicy so forms fail fast. */
export function passwordProblem(password: string, username?: string, email?: string): string | null {
  if (!password.trim()) return 'Password is required'
  if (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
    return `Password must be ${PASSWORD_MIN}-${PASSWORD_MAX} characters`
  }
  if (/\s/.test(password)) return 'Password must not contain spaces'
  if (!/[A-Z]/.test(password) || !/[a-z]/.test(password) || !/[0-9]/.test(password) || !/[^A-Za-z0-9]/.test(password)) {
    return 'Password must include upper-case, lower-case, number and special characters'
  }
  const normalized = password.toLowerCase()
  if (COMMON_PASSWORDS.has(normalized)) return 'Choose a less common password'
  if (username && normalized === username.trim().toLowerCase()) return 'Password must not match the username'
  if (email) {
    const local = email.split('@')[0]?.trim().toLowerCase()
    if (local && normalized === local) return 'Password must not match the email address'
  }
  return null
}
