//! Packed binary AST format. Wire layout matches SPIKE_PLAN §4.2.
//!
//! ```text
//! header (8 bytes):
//!   magic     : 4 bytes 'PMDA'
//!   version   : u8     (= 1)
//!   flags     : u8     (bit 0 = has_html_blocks)
//!   reserved  : u16
//!
//! body (depth-first):
//!   node:
//!     tag           : u8           (NodeTypeCode)
//!     start_offset  : varint (LEB128, u32 max)
//!     end_offset    : varint (delta from start)
//!     extras_len    : varint
//!     extras        : bytes        (per-tag payload)
//!     children_count: varint
//!     children...   : recursive
//! ```
//!
//! Kotlin side decodes lazily via `PackedAstNode`.

use crate::tree_builder::{Node, Tree};

const MAGIC: &[u8; 4] = b"PMDA";
const VERSION: u8 = 1;
const FLAG_HAS_HTML_BLOCKS: u8 = 0b0000_0001;

pub fn pack(tree: &Tree) -> Vec<u8> {
    let mut out = Vec::with_capacity(64);
    out.extend_from_slice(MAGIC);
    out.push(VERSION);
    let mut flags = 0u8;
    if tree.meta.has_html_blocks {
        flags |= FLAG_HAS_HTML_BLOCKS;
    }
    out.push(flags);
    out.push(0); // reserved
    out.push(0);

    pack_node(&tree.root, &mut out);
    out
}

fn pack_node(node: &Node, out: &mut Vec<u8>) {
    out.push(node.type_code.as_byte());

    // start_offset as varint
    write_varint(node.start as u64, out);
    // end_offset stored as delta from start to keep varint short
    let delta = node.end.saturating_sub(node.start) as u64;
    write_varint(delta, out);

    // extras
    write_varint(node.extras.len() as u64, out);
    out.extend_from_slice(&node.extras);

    // children
    write_varint(node.children.len() as u64, out);
    for child in &node.children {
        pack_node(child, out);
    }
}

fn write_varint(mut value: u64, out: &mut Vec<u8>) {
    loop {
        let byte = (value & 0x7F) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        } else {
            out.push(byte | 0x80);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree_builder::TreeMeta;
    use crate::type_mapping::NodeTypeCode;

    fn empty_tree() -> Tree {
        Tree {
            root: Node {
                type_code: NodeTypeCode::Root,
                start: 0,
                end: 0,
                extras: vec![],
                children: vec![],
            },
            meta: TreeMeta { has_html_blocks: false },
        }
    }

    #[test]
    fn header_layout() {
        let t = empty_tree();
        let bytes = pack(&t);
        assert_eq!(&bytes[..4], MAGIC);
        assert_eq!(bytes[4], VERSION);
        assert_eq!(bytes[5], 0); // no html flag
        assert_eq!(bytes[6], 0);
        assert_eq!(bytes[7], 0);
    }

    #[test]
    fn html_flag_set() {
        let mut t = empty_tree();
        t.meta.has_html_blocks = true;
        let bytes = pack(&t);
        assert_eq!(bytes[5], FLAG_HAS_HTML_BLOCKS);
    }

    #[test]
    fn varint_small_values_one_byte() {
        let mut out = Vec::new();
        write_varint(0, &mut out);
        assert_eq!(out, vec![0]);

        let mut out = Vec::new();
        write_varint(127, &mut out);
        assert_eq!(out, vec![127]);

        let mut out = Vec::new();
        write_varint(128, &mut out);
        assert_eq!(out, vec![0x80, 0x01]);
    }
}
