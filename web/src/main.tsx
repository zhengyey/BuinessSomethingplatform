import { ConfigProvider, Button } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import 'antd/dist/reset.css'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, Link, Navigate, Outlet, Route, RouterProvider, Routes, useLocation } from 'react-router-dom'
import { errorText, request, type Session } from './api'
import { Login } from './features/auth/Login'
import { sessionKey, useRefreshSession, useSession } from './features/auth/session'
import { CreateOrder } from './features/orders/CreateOrder'
import { OrderDetail } from './features/orders/OrderDetail'
import { OrderList } from './features/orders/OrderList'
import { ReceiptRecord } from './features/receipts/ReceiptRecord'
import { ReceiptReview, ReviewEntry } from './features/receipts/ReceiptReview'
import { OrderImport } from './features/tasks/FileUpload'
import { TaskDetail } from './features/tasks/TaskDetail'
import { TaskList } from './features/tasks/TaskList'
import { Brand, Failure, Loading } from './ui'
import './styles.css'

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
const theme = {
  token: {
    colorPrimary: '#096e65',
    colorText: '#182b2d',
    borderRadius: 8,
    fontFamily: 'system-ui, "Microsoft YaHei", sans-serif',
  },
}

function Workspace({ session }: { session: Session }) {
  const location = useLocation()
  const refresh = useRefreshSession()
  const unsaved = useRef(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  if (!session.username) {
    return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />
  }

  async function logout() {
    if (unsaved.current && !window.confirm('修改尚未保存，仍要退出登录吗？')) return
    setBusy(true)
    setError('')
    try {
      await request('/logout', undefined, session)
      await refresh()
    } catch (cause) {
      setError(errorText(cause))
      try {
        await refresh()
      } catch {
        // Leave the session banner and logout control available for retry.
      }
    } finally {
      setBusy(false)
    }
  }

  return <>
    <header className="workspace-header">
      <div className="header-inner">
        <Link to="/orders" className="brand-link"><Brand /></Link>
        <div className="account">
          <Link className="header-nav" to="/tasks">任务中心</Link>
          <span title={session.username}>{session.username}</span>
          <Button ghost size="small" loading={busy} onClick={logout}>退出登录</Button>
        </div>
      </div>
    </header>
    <main className="workspace">
      {error && <Failure message={error} />}
      <Outlet context={unsaved} />
    </main>
  </>
}

function App() {
  const session = useSession()
  useEffect(() => {
    const update = () => { void queryClient.invalidateQueries({ queryKey: sessionKey }) }
    window.addEventListener('labreview:session-expired', update)
    return () => window.removeEventListener('labreview:session-expired', update)
  }, [])
  useEffect(() => {
    if (session.data && !session.data.username) {
      queryClient.removeQueries({ queryKey: ['orders'] })
      queryClient.removeQueries({ queryKey: ['tasks'] })
    }
  }, [session.data])

  if (session.isPending) return <main className="workspace"><Loading text="正在检查登录状态" /></main>
  if (session.isError) {
    return <main className="workspace">
      <Failure message={errorText(session.error)} retry={() => void session.refetch()} />
    </main>
  }
  return <Routes>
    <Route path="/login" element={<Login session={session.data} />} />
    <Route element={<Workspace session={session.data} />}>
      <Route path="/orders" element={<OrderList />} />
      <Route path="/orders/new" element={<CreateOrder session={session.data} />} />
      <Route path="/orders/import" element={<OrderImport session={session.data} />} />
      <Route path="/orders/:orderId" element={<OrderDetail session={session.data} />} />
      <Route path="/orders/:orderId/documents/:documentId/review" element={<ReviewEntry session={session.data} />} />
      <Route path="/orders/:orderId/reviews/:reviewId" element={<ReceiptReview session={session.data} />} />
      <Route path="/orders/:orderId/receipts/:receiptId" element={<ReceiptRecord session={session.data} />} />
      <Route path="/tasks" element={<TaskList />} />
      <Route path="/tasks/:taskId" element={<TaskDetail session={session.data} />} />
    </Route>
    <Route path="*" element={<Navigate to="/orders" replace />} />
  </Routes>
}

const router = createBrowserRouter([{ path: '*', element: <App /> }])

createRoot(document.getElementById('root')!).render(
  <ConfigProvider locale={zhCN} theme={theme} button={{ autoInsertSpace: false }}>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </ConfigProvider>,
)
