/* ADDED: About & Help Documentation Page */
import { el } from '../core.js';
import { icon } from '../icons.js';

export function render(container) {
  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { class: 'page-title', text: 'วิธีเล่นและคำสั่งในเกม' }),
        el('p', { class: 'hint', text: 'คู่มือระบบตลาด การซื้อขายไอเท็ม และการถือครองกรรมสิทธิ์ที่ดิน Time SMP' }))
    )
  );

  const commands = [
    { cmd: '/bal', desc: 'ตรวจสอบยอดเงินคงเหลือในกระเป๋า' },
    { cmd: '/pay <ผู้เล่น> <จำนวน>', desc: 'โอนเงินให้ผู้เล่นคนอื่นในเซิร์ฟเวอร์โดยตรง' },
    { cmd: '/buy <ไอเท็ม> <จำนวน>', desc: 'สั่งซื้อไอเท็มจากร้านค้าเซิร์ฟเวอร์ ของจะเข้ากล่องจดหมาย' },
    { cmd: '/sell hand', desc: 'ขายไอเท็มที่กำลังถือในมือหลักให้ร้านค้าเซิร์ฟเวอร์' },
    { cmd: '/sell all', desc: 'สรุปและขายไอเท็มที่ไม่มี enchant ทั้งหมดในกระเป๋าให้ร้านค้า' },
    { cmd: '/mail', desc: 'ดูรายการไอเท็มที่รอรับในกล่องจดหมาย' },
    { cmd: '/mail claim', desc: 'รับไอเท็มทั้งหมดในกล่องเข้ากระเป๋าตัวละคร (เช็คช่องว่างก่อนรับ)' },
    { cmd: '/ah', desc: 'เปิดดูรายการประมูลของผู้เล่นทั้งหมด' },
    { cmd: '/ah sell <ราคาเริ่ม> [ซื้อทันที] [ชั่วโมง]', desc: 'นำของในมือหลักขึ้นตลาดประมูล' },
    { cmd: '/ah bid <id> <จำนวน>', desc: 'เสนอราคาประมูลแข่งกับผู้เล่นอื่น' },
    { cmd: '/ah cancel <id>', desc: 'ยกเลิกรายการประมูลที่ยังไม่มีคนเสนอราคา' },
    { cmd: '/claim', desc: 'จองพื้นที่ chunk 500×500 บล็อกที่คุณกำลังยืนอยู่' },
    { cmd: '/claim transfer <ผู้เล่น>', desc: 'โอนกรรมสิทธิ์เขตที่คุณยืนอยู่ให้แก่ผู้เล่นอื่น' },
    { cmd: '/claimmap here', desc: 'ตรวจสอบข้อมูลและเจ้าของพื้นที่ปัจจุบัน' }
  ];

  const tbody = el('tbody');
  for (const c of commands) {
    tbody.append(
      el('tr', null,
        el('td', null, el('code', { class: 'mono', text: c.cmd })),
        el('td', { text: c.desc })
      )
    );
  }

  const table = el('table', null,
    el('thead', null,
      el('tr', null,
        el('th', { style: 'width: 320px;', text: 'คำสั่ง (COMMAND)' }),
        el('th', { text: 'คำอธิบาย (DESCRIPTION)' })
      )
    ),
    tbody
  );

  const guide = el('div', { class: 'card' },
    el('h3', { style: 'margin-bottom: 12px;', text: 'ระบบสำคัญในเซิร์ฟเวอร์' }),
    el('div', { class: 'ruled-list' },
      el('div', { class: 'ruled-row' },
        el('div', { class: 'row-main' },
          el('div', { class: 'row-title', text: '1. กล่องรับของ (Mailbox Depot)' }),
          el('div', { class: 'row-meta', text: 'ของที่ซื้อจากร้านหรือชนะประมูลจะเข้ากล่องรับของก่อนเสมอ เมื่อออนไลน์ในเกมให้พิมพ์ /mail claim หรือกดปุ่มรับของบนหน้าเว็บ ระบบจะตรวจสอบช่องว่างในกระเป๋าเพื่อป้องกันของสูญหาย' })
        )
      ),
      el('div', { class: 'ruled-row' },
        el('div', { class: 'row-main' },
          el('div', { class: 'row-title', text: '2. ตารางไอเท็ม 1×1 สำหรับลงขาย' }),
          el('div', { class: 'row-meta', text: 'ในหน้าลงขาย ระบบจะแสดงไอเท็มทั้งหมดที่คุณมี ทั้งในกระเป๋าตัวละคร ใน Ender Chest และในกล่องในพื้นที่ที่คุณจองไว้ สามารถแตะเลือกไอเท็มแล้วตั้งราคาขายได้ทันที' })
        )
      ),
      el('div', { class: 'ruled-row' },
        el('div', { class: 'row-main' },
          el('div', { class: 'row-title', text: '3. แผนที่สำรวจเฉพาะส่วนที่ปลดล็อค' }),
          el('div', { class: 'row-meta', text: 'แผนที่จะเปิดแสดงเฉพาะส่วนที่ผู้เล่นเดินสำรวจแล้วเท่านั้น และจะเลื่อนจุดกึ่งกลางไปยังพิกัดล่าสุดของคุณโดยอัตโนมัติเมื่อเปิดหน้าแผนที่' })
        )
      )
    )
  );

  container.append(table, guide);
}
