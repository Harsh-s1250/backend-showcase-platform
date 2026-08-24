import { Routes, Route } from 'react-router-dom'
import { HomePage } from './pages/HomePage'
import { ShowcasePage } from './pages/ShowcasePage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/showcase" element={<ShowcasePage />} />
    </Routes>
  )
}
