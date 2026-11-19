import { Button } from 'antd'
import { useState, type FormEvent } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { errorText, request, type Session } from '../../api'
import { Brand, Failure } from '../../ui'
import { useRefreshSession } from './session'

export function Login({ session }: { session: Session }) {
  const { state } = useLocation()
  const refreshSession = useRefreshSession()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  if (session.username) {
    const from = typeof state?.from === 'string' && /^\/(orders|tasks)(?:[/?]|$)/.test(state.from)
      ? state.from : '/orders'
    return <Navigate to={from} replace />
  }

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) return
    const values = new FormData(event.currentTarget)
    setError('')
    setSubmitting(true)
    try {
      await request('/login', new URLSearchParams({
        username: String(values.get('username')).trim(),
        password: String(values.get('password')),
      }), session)
      await refreshSession()
    } catch (cause) {
      setError(errorText(cause))
      try {
        await refreshSession()
      } catch {
        // Keep the original login error visible.
      }
    } finally {
      setSubmitting(false)
    }
  }

  return <main className="login-page">
    <div className="login-band"><Brand /></div>
    <section className="login-card">
      <h1>收货员登录</h1>
      <form className="form-stack" onSubmit={login}>
        {error && <Failure message={error} />}
        <label>用户名<input name="username" autoComplete="username" maxLength={100} required /></label>
        <label>密码<input name="password" type="password" autoComplete="current-password" required /></label>
        <Button type="primary" htmlType="submit" size="large" loading={submitting}>登录</Button>
      </form>
    </section>
  </main>
}
