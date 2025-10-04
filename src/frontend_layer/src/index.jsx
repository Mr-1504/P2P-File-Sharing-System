import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import 'bootstrap/dist/css/bootstrap.min.css';
import './styles/index.css';
import "./utils/i18n";

const root = createRoot(document.getElementById('root'));
root.render(<App />);
