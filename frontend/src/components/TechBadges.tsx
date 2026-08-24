import './TechBadges.css'

interface TechBadgesProps {
  items: string[]
}

export function TechBadges({ items }: TechBadgesProps) {
  const visible = items.filter(Boolean)
  if (visible.length === 0) return null

  return (
    <ul className="tech-badges" aria-label="Technology stack">
      {visible.map((item) => (
        <li key={item} className="tech-badges__item">
          {item}
        </li>
      ))}
    </ul>
  )
}
