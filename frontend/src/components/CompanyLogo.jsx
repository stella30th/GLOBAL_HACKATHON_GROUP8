import { useState } from 'react';

/**
 * Company avatar with a generated fallback.
 *
 * Job boards supply a logo URL only some of the time, and the ones they do supply frequently 404 or
 * hotlink-block, which rendered a broken-image icon. The previous fallback was a single stock office
 * photo from Unsplash, so every company without a logo looked identical and the photo itself could
 * fail to load too.
 *
 * When there is no usable image this draws initials on a colour derived from the company name: it
 * needs no network, never breaks, and gives each employer a stable, distinguishable mark.
 */
export default function CompanyLogo({ company, src, size = 44, radius = 10 }) {
  const [failedSrc, setFailedSrc] = useState(null);

  const name = (company || '').trim();
  const showImage = src && failedSrc !== src;

  if (showImage) {
    return (
      <img
        src={src}
        alt={name}
        className="company-avatar"
        style={{ width: size, height: size, borderRadius: radius }}
        loading="lazy"
        onError={() => setFailedSrc(src)}
      />
    );
  }

  return (
    <div
      className="company-avatar company-avatar-fallback"
      style={{
        width: size,
        height: size,
        borderRadius: radius,
        background: gradientFor(name),
        fontSize: Math.round(size * 0.4),
        // The shared .company-avatar rule adds a white plate for real logos; the generated mark
        // paints its own background edge to edge.
        padding: 0,
      }}
      // The initials are decorative; the company name is always shown next to this element.
      aria-hidden="true"
    >
      {initialsOf(name)}
    </div>
  );
}

/** Up to two initials from the meaningful words of a company name. */
function initialsOf(name) {
  if (!name) return '?';
  const words = name
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .split(/\s+/)
    .filter((w) => w && !STOP_WORDS.has(w.toLowerCase()));

  if (words.length === 0) return name.charAt(0).toUpperCase();
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

// Legal suffixes and filler carry no identity, so "The Muse Inc" reads as "TM", not "TH".
const STOP_WORDS = new Set([
  'the', 'and', 'inc', 'llc', 'ltd', 'limited', 'gmbh', 'co', 'corp', 'corporation',
  'group', 'holdings', 'company', 'plc', 'sa', 'ag', 'bv', 'ab', 'oy', 'as', 'pte',
]);

/** Stable colour per company: the same employer always gets the same mark. */
function gradientFor(name) {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) % 360;
  }
  const hue = hash;
  // A second hue nearby keeps the gradient readable rather than clashing.
  const hue2 = (hue + 38) % 360;
  return `linear-gradient(135deg, hsl(${hue} 58% 46%), hsl(${hue2} 62% 34%))`;
}
