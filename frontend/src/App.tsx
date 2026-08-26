import { Routes, Route } from 'react-router-dom'
import { HomePage } from './pages/HomePage'
import { ShowcasePage } from './pages/ShowcasePage'
import { DashboardPage } from './pages/DashboardPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/showcase" element={<ShowcasePage />} />
      <Route path="/dashboard" element={<DashboardPage />} />
    </Routes>
  )
}
