// import React from "react";

// export default function ConfirmDialog({ open, onClose }) {
//   if (!open) return null;

//   return (
//     <div className="fixed inset-0 flex items-center justify-center bg-black/50 z-50">
//       <div className="bg-white p-6 rounded-2xl shadow-xl">
//         <p className="text-lg font-semibold mb-4">
//           Tệp đã tồn tại, bạn muốn làm gì?
//         </p>
//         <div className="flex gap-3">
//           <button
//             className="px-4 py-2 bg-gray-500 text-white rounded-lg"
//             onClick={() => onClose(-1)}
//           >
//             Hủy
//           </button>
//           <button
//             className="px-4 py-2 bg-yellow-500 text-white rounded-lg"
//             onClick={() => onClose(0)}
//           >
//             Tiếp tục
//           </button>
//           <button
//             className="px-4 py-2 bg-green-600 text-white rounded-lg"
//             onClick={() => onClose(1)}
//           >
//             Thay thế
//           </button>
//         </div>
//       </div>
//     </div>
//   );
// }


import React from "react";

export default function ConfirmDialog({ 
  open, 
  title = "Xác nhận", 
  message, 
  buttons = [], 
  onClose 
}) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 flex items-center justify-center bg-black/50 z-50">
      <div className="bg-white p-6 rounded-2xl shadow-xl min-w-[300px]">
        <p className="text-lg font-semibold mb-4">{title}</p>
        {message && <p className="mb-4">{message}</p>}
        <div className="flex gap-3 justify-end">
          {buttons.map((btn, idx) => (
            <button
              key={idx}
              className={`px-4 py-2 rounded-lg ${btn.className}`}
              onClick={() => onClose(btn.value)}
            >
              {btn.label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
