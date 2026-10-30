import {
  ArrowLeftRight,
  ArrowRight,
  Boxes,
  Building2,
  ClipboardList,
  IndianRupee,
  PackagePlus,
  RefreshCw,
  ShoppingCart,
  TriangleAlert,
  TrendingUp,
} from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Button, Card, ErrorState, LoadingState, PageHeader, StatCard, StatusBadge } from '../components/ui'
import { useApi } from '../hooks/useApi'
import { api } from '../lib/api'
import { formatDate, money, moneyCompact, qty } from '../lib/format'
import { useAuthStore } from '../store/auth'
import type { Dashboard } from '../types'

function greeting(): string {
  const hour = new Date().getHours()
  if (hour < 12) return 'Good morning'
  if (hour < 17) return 'Good afternoon'
  return 'Good evening'
}

function EmptyRow({ message }: { message: string }) {
  return <p className="px-4 py-6 text-center text-xs text-slate-400">{message}</p>
}

export function DashboardPage() {
  const navigate = useNavigate()
  const { user, selectedBranchId, allBranches, branches, hasPermission, store } = useAuthStore()
  const branchId = selectedBranchId
  const branchLabel = branchId ? branches.find((branch) => branch.id === branchId)?.branchName : allBranches ? 'All branches' : ''

  const { data, loading, error, reload } = useApi<Dashboard>(
    () => api.get('/dashboard', { params: { branchId: branchId ?? undefined } }).then((response) => response.data),
    [branchId],
  )

  if (loading && !data) return <LoadingState label="Loading your store overview…" />
  if (error) return <ErrorState message={error} onRetry={reload} />
  if (!data) return null

  const summary = data.summary

  return (
    <div className="space-y-5">
      <PageHeader
        eyebrow={formatDate(new Date().toISOString().slice(0, 10))}
        title={`${greeting()}, ${user?.fullName?.split(' ')[0] ?? 'there'}`}
        subtitle={`${store?.storeName ?? 'Your store'} · ${branchLabel || 'All branches'}`}
        actions={
          <>
            <Button variant="outline" size="sm" icon={<RefreshCw className="h-4 w-4" />} onClick={reload}>
              Refresh
            </Button>
            {hasPermission('SALES_MANAGE') && (
              <Button size="sm" icon={<ShoppingCart className="h-4 w-4" />} onClick={() => navigate('/pos')}>
                New sale
              </Button>
            )}
          </>
        }
      />

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Today's sales"
          value={money(summary.todaySalesAmount)}
          hint="Completed bills today"
          icon={<IndianRupee className="h-5 w-5" />}
          tone="brand"
        />
        <StatCard
          label="Today's purchases"
          value={money(summary.todayPurchaseAmount)}
          hint="Stock received today"
          icon={<ClipboardList className="h-5 w-5" />}
          tone="blue"
        />
        <StatCard
          label="Inventory value"
          value={money(summary.totalStockValue)}
          hint={`${qty(data.stockSummary.totalQuantity)} units on hand`}
          icon={<Boxes className="h-5 w-5" />}
          tone="green"
        />
        <StatCard
          label="Low stock alerts"
          value={summary.totalLowStockItems}
          hint="Items at or below minimum"
          icon={<TriangleAlert className="h-5 w-5" />}
          tone={summary.totalLowStockItems > 0 ? 'rose' : 'purple'}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-5 py-4">
            <div>
              <h2 className="text-sm font-bold text-slate-800">Branch performance today</h2>
              <p className="mt-0.5 text-xs text-slate-500">Sales and purchase value by branch</p>
            </div>
            <span className="flex items-center gap-1 rounded-full bg-brand-50 px-2.5 py-1 text-[11px] font-semibold text-brand-700">
              <TrendingUp className="h-3.5 w-3.5" /> Today
            </span>
          </div>
          <div className="h-72 px-2 py-3 sm:px-4">
            {data.branchSummary.length === 0 ? (
              <EmptyRow message="No branch activity to display yet." />
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={data.branchSummary} barGap={4}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#eef1f5" vertical={false} />
                  <XAxis dataKey="branchName" tick={{ fontSize: 11, fill: '#64748b' }} axisLine={false} tickLine={false} />
                  <YAxis
                    tick={{ fontSize: 10, fill: '#94a3b8' }}
                    axisLine={false}
                    tickLine={false}
                    width={54}
                    tickFormatter={(value) => moneyCompact(Number(value))}
                  />
                  <Tooltip
                    formatter={(value) => money(Number(value))}
                    contentStyle={{ borderRadius: 10, border: '1px solid #e2e8f0', fontSize: 12 }}
                  />
                  <Legend wrapperStyle={{ fontSize: 11, paddingTop: 8 }} />
                  <Bar dataKey="salesToday" name="Sales" fill="#ea580c" radius={[5, 5, 0, 0]} maxBarSize={44} />
                  <Bar dataKey="purchasesToday" name="Purchases" fill="#94a3b8" radius={[5, 5, 0, 0]} maxBarSize={44} />
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </Card>

        <Card>
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
            <div>
              <h2 className="text-sm font-bold text-slate-800">Top selling items</h2>
              <p className="mt-0.5 text-xs text-slate-500">Last 30 days by revenue</p>
            </div>
            <Link to="/reports" className="link text-xs">
              Reports
            </Link>
          </div>
          <div className="space-y-3.5 px-5 py-4">
            {data.topSellingItems.length === 0 && <EmptyRow message="No sales in the last 30 days." />}
            {data.topSellingItems.slice(0, 5).map((item, index) => {
              const max = Math.max(...data.topSellingItems.map((entry) => Number(entry.revenue)), 1)
              return (
                <div key={item.itemId}>
                  <div className="mb-1 flex items-center justify-between gap-2">
                    <p className="truncate text-xs font-semibold text-slate-700">
                      <span className="mr-1.5 text-slate-400">{index + 1}.</span>
                      {item.itemName}
                    </p>
                    <p className="shrink-0 text-xs font-bold text-slate-800">{moneyCompact(item.revenue)}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-slate-100">
                      <div
                        className="h-full rounded-full bg-gradient-to-r from-brand-400 to-brand-600"
                        style={{ width: `${Math.max(6, (Number(item.revenue) / max) * 100)}%` }}
                      />
                    </div>
                    <span className="w-20 shrink-0 text-right text-[10px] text-slate-500">
                      {qty(item.quantitySold)} {item.unit}
                    </span>
                  </div>
                </div>
              )
            })}
          </div>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
            <div>
              <h2 className="text-sm font-bold text-slate-800">Branch stock overview</h2>
              <p className="mt-0.5 text-xs text-slate-500">Inventory health across locations</p>
            </div>
            <Building2 className="h-4 w-4 text-slate-400" />
          </div>
          <div className="thin-scrollbar overflow-x-auto">
            <table className="w-full">
              <thead className="bg-slate-50/70">
                <tr>
                  <th className="th">Branch</th>
                  <th className="th text-right">Sales today</th>
                  <th className="th text-right">Purchases</th>
                  <th className="th text-right">Stock value</th>
                  <th className="th text-right">Low stock</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.branchSummary.map((branch) => (
                  <tr key={branch.branchId} className="hover:bg-slate-50/60">
                    <td className="td font-semibold text-slate-800">{branch.branchName}</td>
                    <td className="td text-right">{money(branch.salesToday)}</td>
                    <td className="td text-right text-slate-500">{money(branch.purchasesToday)}</td>
                    <td className="td text-right">{money(branch.stockValue)}</td>
                    <td className="td text-right">
                      {branch.lowStockItems > 0 ? (
                        <span className="font-semibold text-rose-600">{branch.lowStockItems}</span>
                      ) : (
                        <span className="text-emerald-600">Clear</span>
                      )}
                    </td>
                  </tr>
                ))}
                {data.branchSummary.length === 0 && (
                  <tr>
                    <td colSpan={5}>
                      <EmptyRow message="No branches available." />
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </Card>

        <Card>
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
            <div>
              <h2 className="text-sm font-bold text-slate-800">Low stock alerts</h2>
              <p className="mt-0.5 text-xs text-slate-500">Replenish before you run out</p>
            </div>
            <Link to="/low-stock" className="link text-xs">
              View all
            </Link>
          </div>
          <div className="divide-y divide-slate-100">
            {data.lowStock.length === 0 && <EmptyRow message="All items are above their minimum level." />}
            {data.lowStock.slice(0, 6).map((row) => (
              <div key={`${row.branchId}-${row.itemId}`} className="flex items-center gap-3 px-5 py-2.5">
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-rose-50 text-rose-500">
                  <TriangleAlert className="h-4 w-4" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-xs font-semibold text-slate-800">{row.itemName}</p>
                  <p className="truncate text-[10px] text-slate-500">{row.branchName}</p>
                </div>
                <div className="text-right">
                  <p className="text-xs font-bold text-rose-600">
                    {qty(row.currentStock)} {row.unit}
                  </p>
                  <p className="text-[10px] text-slate-400">min {qty(row.minimumStock)}</p>
                </div>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
            <h2 className="text-sm font-bold text-slate-800">Recent purchases</h2>
            <Link to="/purchases" className="link text-xs">
              Purchase history
            </Link>
          </div>
          <div className="divide-y divide-slate-100">
            {data.recentPurchases.length === 0 && <EmptyRow message="No purchases recorded yet." />}
            {data.recentPurchases.map((entry) => (
              <div key={entry.id} className="flex items-center gap-3 px-5 py-2.5">
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-sky-50 text-sky-600">
                  <ClipboardList className="h-4 w-4" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-xs font-semibold text-slate-800">{entry.number}</p>
                  <p className="truncate text-[10px] text-slate-500">
                    {entry.partyName ?? 'Supplier'} · {entry.branchName} · {formatDate(entry.date)}
                  </p>
                </div>
                <div className="text-right">
                  <p className="text-xs font-bold text-slate-800">{money(entry.amount)}</p>
                  <StatusBadge status={entry.status} />
                </div>
              </div>
            ))}
          </div>
        </Card>

        <Card>
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
            <h2 className="text-sm font-bold text-slate-800">Recent sales</h2>
            <Link to="/sales" className="link text-xs">
              Sales history
            </Link>
          </div>
          <div className="divide-y divide-slate-100">
            {data.recentSales.length === 0 && <EmptyRow message="No sales recorded yet." />}
            {data.recentSales.map((entry) => (
              <div key={entry.id} className="flex items-center gap-3 px-5 py-2.5">
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600">
                  <ShoppingCart className="h-4 w-4" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-xs font-semibold text-slate-800">{entry.number}</p>
                  <p className="truncate text-[10px] text-slate-500">
                    {entry.partyName ?? 'Walk-in customer'} · {entry.branchName} · {formatDate(entry.date)}
                  </p>
                </div>
                <div className="text-right">
                  <p className="text-xs font-bold text-slate-800">{money(entry.amount)}</p>
                  <StatusBadge status={entry.status} />
                </div>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <div className="flex flex-wrap items-center gap-2 rounded-xl border border-slate-200/80 bg-white px-4 py-3 shadow-card">
        <span className="mr-1 text-xs font-semibold text-slate-500">Quick actions</span>
        {hasPermission('PURCHASE_MANAGE') && (
          <Button variant="outline" size="sm" icon={<ClipboardList className="h-3.5 w-3.5" />} onClick={() => navigate('/purchases?new=1')}>
            Record purchase
          </Button>
        )}
        {hasPermission('INVENTORY_ADJUST') && (
          <Button variant="outline" size="sm" icon={<PackagePlus className="h-3.5 w-3.5" />} onClick={() => navigate('/inventory?adjust=1')}>
            Adjust stock
          </Button>
        )}
        {hasPermission('TRANSFER_MANAGE') && (
          <Button variant="outline" size="sm" icon={<ArrowLeftRight className="h-3.5 w-3.5" />} onClick={() => navigate('/transfers?new=1')}>
            New transfer
          </Button>
        )}
        <Button variant="ghost" size="sm" className="ml-auto" icon={<ArrowRight className="h-3.5 w-3.5" />} onClick={() => navigate('/reports')}>
          Open reports
        </Button>
      </div>
    </div>
  )
}
