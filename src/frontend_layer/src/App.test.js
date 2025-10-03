import { render, screen } from '@testing-library/react';
import App from './App';
import './i18n';

test('renders application title', () => {
  render(<App />);
  const titleElement = screen.getByText(/P2P File Sharing/i);
  expect(titleElement).toBeInTheDocument();
});
