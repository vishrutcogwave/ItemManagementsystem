import { lazy, Suspense, useEffect } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { AccessDenied, PublicOnly, RequireAuth, RequirePermission } from './components/layout/guards'
import { LoadingState, Toaster } from './components/ui'
import { api, PASSWORD_CHANGE_REQUIRED_EVENT, UNAUTHORIZED_EVENT } from './lib/api'
import { useAuthStore } from './store/auth'
import { notifyError } from './store/ui'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import type { AuthResponse } from './types'

const DashboardPage = lazy(() => import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })))
const PosPage = lazy(() => import('./pages/PosPage').then((m) => ({ default: m.PosPage })))
const SalesPage = lazy(() => import('./pages/SalesPage').then((m) => ({ default: m.SalesPage })))
const SalesEntryPage = lazy(() =>
  import('./pages/SalesEntryPage').then((m) => ({ default: m.SalesEntryPage })),
)
const ItemsPage = lazy(() => import('./pages/ItemsPage').then((m) => ({ default: m.ItemsPage })))
const ItemImportsPage = lazy(() =>
  import('./pages/ItemImportsPage').then((m) => ({ default: m.ItemImportsPage })),
)
const CategoriesPage = lazy(() => import('./pages/CategoriesPage').then((m) => ({ default: m.CategoriesPage })))
const TaxMasterPage = lazy(() => import('./pages/TaxMasterPage').then((m) => ({ default: m.TaxMasterPage })))
const UnitMasterPage = lazy(() => import('./pages/UnitMasterPage').then((m) => ({ default: m.UnitMasterPage })))
const SuppliersPage = lazy(() => import('./pages/SuppliersPage').then((m) => ({ default: m.SuppliersPage })))
const SupplierPaymentsPage = lazy(() =>
  import('./pages/SupplierPaymentsPage').then((m) => ({ default: m.SupplierPaymentsPage })),
)
const PurchasesPage = lazy(() => import('./pages/PurchasesPage').then((m) => ({ default: m.PurchasesPage })))
const InventoryPage = lazy(() => import('./pages/InventoryPage').then((m) => ({ default: m.InventoryPage })))
const TransfersPage = lazy(() => import('./pages/TransfersPage').then((m) => ({ default: m.TransfersPage })))
const LowStockPage = lazy(() => import('./pages/LowStockPage').then((m) => ({ default: m.LowStockPage })))
const ReportsPage = lazy(() => import('./pages/ReportsPage').then((m) => ({ default: m.ReportsPage })))
const PrintHistoryPage = lazy(() =>
  import('./pages/PrintHistoryPage').then((m) => ({ default: m.PrintHistoryPage })),
)
const SettingsPage = lazy(() => import('./pages/SettingsPage').then((m) => ({ default: m.SettingsPage })))
const PlatformStoresPage = lazy(() =>
  import('./pages/PlatformStoresPage').then((m) => ({ default: m.PlatformStoresPage })),
)
const PlatformAccountsPage = lazy(() =>
  import('./pages/PlatformAccountsPage').then((m) => ({ default: m.PlatformAccountsPage })),
)
const PlatformAuditPage = lazy(() =>
  import('./pages/PlatformAuditPage').then((m) => ({ default: m.PlatformAuditPage })),
)
const ChangePasswordPage = lazy(() =>
  import('./pages/ChangePasswordPage').then((m) => ({ default: m.ChangePasswordPage })),
)
const RegisterPage = lazy(() => import('./pages/RegisterPage').then((m) => ({ default: m.RegisterPage })))

function HomeRoute() {
  const platformAdmin = useAuthStore((state) => state.platformAdmin)
  const hasPermission = useAuthStore((state) => state.hasPermission)
  if (platformAdmin) return <Navigate to="/platform/stores" replace />
  if (!hasPermission('DASHBOARD_VIEW')) return <AccessDenied />
  return <DashboardPage />
}

function SessionWatcher() {
  const logout = useAuthStore((state) => state.logout)
  const setSession = useAuthStore((state) => state.setSession)
  const markSessionReady = useAuthStore((state) => state.markSessionReady)
  const requirePasswordChange = useAuthStore((state) => state.requirePasswordChange)
  const navigate = useNavigate()

  // The session cookie is HttpOnly, so the only way to know whether the
  // browser is signed in is to ask the server once on start-up.
  useEffect(() => {
    let active = true
    api
      .get<AuthResponse>('/auth/me')
      .then(({ data }) => {
        if (active) setSession(data)
      })
      .catch(() => {
        if (active) logout()
      })
      .finally(() => {
        if (active) markSessionReady()
      })
    return () => {
      active = false
    }
  }, [logout, markSessionReady, setSession])

  useEffect(() => {
    const handleUnauthorized = () => {
      const hadSession = Boolean(useAuthStore.getState().user)
      logout()
      if (hadSession) {
        notifyError('Your session has expired. Please sign in again.')
        navigate('/login', { replace: true })
      }
    }
    const handlePasswordChange = () => {
      requirePasswordChange()
      navigate('/change-password', { replace: true })
    }
    window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
    window.addEventListener(PASSWORD_CHANGE_REQUIRED_EVENT, handlePasswordChange)
    return () => {
      window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
      window.removeEventListener(PASSWORD_CHANGE_REQUIRED_EVENT, handlePasswordChange)
    }
  }, [logout, navigate, requirePasswordChange])

  return null
}

function ScrollToTop() {
  const { pathname } = useLocation()
  useEffect(() => {
    window.scrollTo({ top: 0 })
  }, [pathname])
  return null
}

export default function App() {
  return (
    <BrowserRouter>
      <SessionWatcher />
      <ScrollToTop />
      <Suspense
        fallback={
          <div className="flex min-h-screen items-center justify-center">
            <LoadingState />
          </div>
        }
      >
        <Routes>
        <Route
          path="/login"
          element={
            <PublicOnly>
              <LoginPage />
            </PublicOnly>
          }
        />
        <Route
          path="/register"
          element={
            <PublicOnly>
              <RegisterPage />
            </PublicOnly>
          }
        />

        <Route
          path="/change-password"
          element={
            <RequireAuth>
              <ChangePasswordPage />
            </RequireAuth>
          }
        />

        <Route
          element={
            <RequireAuth>
              <AppLayout />
            </RequireAuth>
          }
        >
          <Route index element={<HomeRoute />} />
          <Route
            path="pos"
            element={
              <RequirePermission permission="SALES_MANAGE">
                <PosPage />
              </RequirePermission>
            }
          />
          <Route
            path="sales/new"
            element={
              <RequirePermission permission="SALES_MANAGE">
                <SalesEntryPage />
              </RequirePermission>
            }
          />
          <Route
            path="sales"
            element={
              <RequirePermission permission="SALES_VIEW">
                <SalesPage />
              </RequirePermission>
            }
          />
          <Route
            path="items"
            element={
              <RequirePermission permission="ITEM_VIEW">
                <ItemsPage />
              </RequirePermission>
            }
          />
          <Route
            path="items/imports"
            element={
              <RequirePermission anyOf={['ITEM_VIEW', 'INVENTORY_VIEW']}>
                <ItemImportsPage />
              </RequirePermission>
            }
          />
          <Route
            path="categories"
            element={
              <RequirePermission permission="CATEGORY_VIEW">
                <CategoriesPage />
              </RequirePermission>
            }
          />
          <Route
            path="taxes"
            element={
              <RequirePermission permission="TAX_VIEW">
                <TaxMasterPage />
              </RequirePermission>
            }
          />
          <Route
            path="units"
            element={
              <RequirePermission permission="UNIT_VIEW">
                <UnitMasterPage />
              </RequirePermission>
            }
          />
          <Route
            path="suppliers"
            element={
              <RequirePermission permission="SUPPLIER_VIEW">
                <SuppliersPage />
              </RequirePermission>
            }
          />
          <Route
            path="purchases"
            element={
              <RequirePermission permission="PURCHASE_VIEW">
                <PurchasesPage />
              </RequirePermission>
            }
          />
          <Route
            path="supplier-payments"
            element={
              <RequirePermission permission="PURCHASE_VIEW">
                <SupplierPaymentsPage />
              </RequirePermission>
            }
          />
          <Route
            path="inventory"
            element={
              <RequirePermission permission="INVENTORY_VIEW">
                <InventoryPage />
              </RequirePermission>
            }
          />
          <Route
            path="transfers"
            element={
              <RequirePermission permission="TRANSFER_VIEW">
                <TransfersPage />
              </RequirePermission>
            }
          />
          <Route
            path="low-stock"
            element={
              <RequirePermission permission="INVENTORY_VIEW">
                <LowStockPage />
              </RequirePermission>
            }
          />
          <Route
            path="reports"
            element={
              <RequirePermission permission="REPORT_VIEW">
                <ReportsPage />
              </RequirePermission>
            }
          />
          <Route
            path="print-history"
            element={
              <RequirePermission permission="SALES_VIEW">
                <PrintHistoryPage />
              </RequirePermission>
            }
          />
          <Route
            path="settings"
            element={
              <RequirePermission anyOf={['STORE_VIEW', 'USER_VIEW', 'ROLE_VIEW', 'BRANCH_VIEW', 'PRINTER_VIEW']}>
                <SettingsPage />
              </RequirePermission>
            }
          />
          <Route
            path="platform/stores"
            element={
              <RequirePermission permission="PLATFORM_MANAGE">
                <PlatformStoresPage />
              </RequirePermission>
            }
          />
          <Route
            path="platform/accounts"
            element={
              <RequirePermission permission="PLATFORM_MANAGE">
                <PlatformAccountsPage />
              </RequirePermission>
            }
          />
          <Route
            path="platform/audit"
            element={
              <RequirePermission permission="PLATFORM_MANAGE">
                <PlatformAuditPage />
              </RequirePermission>
            }
          />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </Suspense>
      <Toaster />
    </BrowserRouter>
  )
}
