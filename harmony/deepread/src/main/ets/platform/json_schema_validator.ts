// JsonSchemaValidator — 轻量 JSON schema 校验
// 支持 type/required/properties/items/enum/minLength/maxLength/minimum/maximum
// 够 Deep Read stage tool 参数校验用,不需要 ajv 全功能

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

export interface JsonSchema {
  type?: 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array' | 'null';
  required?: string[];
  properties?: Record<string, JsonSchema>;
  items?: JsonSchema;
  enum?: JsonValue[];
  minLength?: number;
  maxLength?: number;
  minimum?: number;
  maximum?: number;
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

export const validateJsonSchema = (value: unknown, schema: JsonSchema, path: string = ''): ValidationResult => {
  const errors: string[] = [];
  validateNode(value, schema, path, errors);
  return { valid: errors.length === 0, errors };
};

const validateNode = (value: unknown, schema: JsonSchema, path: string, errors: string[]): void => {
  if (schema.type !== undefined) {
    if (!checkType(value, schema.type)) {
      errors.push(`${path || 'root'}: expected ${schema.type}, got ${actualType(value)}`);
      return;
    }
  }
  if (schema.enum !== undefined && !schema.enum.some(v => deepEqual(v, value))) {
    errors.push(`${path || 'root'}: value not in enum`);
  }
  if (schema.type === 'string' && typeof value === 'string') {
    if (schema.minLength !== undefined && value.length < schema.minLength) {
      errors.push(`${path}: string shorter than minLength ${schema.minLength}`);
    }
    if (schema.maxLength !== undefined && value.length > schema.maxLength) {
      errors.push(`${path}: string longer than maxLength ${schema.maxLength}`);
    }
  }
  if ((schema.type === 'number' || schema.type === 'integer') && typeof value === 'number') {
    if (schema.minimum !== undefined && value < schema.minimum) {
      errors.push(`${path}: number less than minimum ${schema.minimum}`);
    }
    if (schema.maximum !== undefined && value > schema.maximum) {
      errors.push(`${path}: number greater than maximum ${schema.maximum}`);
    }
  }
  if (schema.type === 'object' && typeof value === 'object' && value !== null && !Array.isArray(value)) {
    const obj = value as Record<string, unknown>;
    if (schema.required) {
      for (const req of schema.required) {
        if (!(req in obj)) {
          errors.push(`${path || 'root'}: missing required property '${req}'`);
        }
      }
    }
    if (schema.properties) {
      for (const [key, subSchema] of Object.entries(schema.properties)) {
        if (key in obj) {
          validateNode(obj[key], subSchema, path ? `${path}.${key}` : key, errors);
        }
      }
    }
  }
  if (schema.type === 'array' && Array.isArray(value)) {
    if (schema.items) {
      for (let i = 0; i < value.length; i++) {
        validateNode(value[i], schema.items, `${path}[${i}]`, errors);
      }
    }
  }
};

const checkType = (value: unknown, type: JsonSchema['type']): boolean => {
  switch (type) {
    case 'string': return typeof value === 'string';
    case 'number': return typeof value === 'number';
    case 'integer': return typeof value === 'number' && Number.isInteger(value);
    case 'boolean': return typeof value === 'boolean';
    case 'null': return value === null;
    case 'object': return typeof value === 'object' && value !== null && !Array.isArray(value);
    case 'array': return Array.isArray(value);
    default: return true;
  }
};

const actualType = (value: unknown): string => {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value;
};

const deepEqual = (a: unknown, b: unknown): boolean => {
  if (a === b) return true;
  if (typeof a !== typeof b) return false;
  if (typeof a !== 'object' || a === null || b === null) return false;
  return JSON.stringify(a) === JSON.stringify(b);
};
